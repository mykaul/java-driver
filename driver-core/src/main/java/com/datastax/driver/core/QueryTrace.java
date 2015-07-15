/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.util.concurrent.Uninterruptibles;

import com.datastax.driver.core.exceptions.TraceRetrievalException;

/**
 * The Cassandra trace for a query.
 * <p>
 * A trace is generated by Cassandra when query tracing is enabled for the
 * query. The trace itself is stored in Cassandra in the {@code sessions} and
 * {@code events} table in the {@code system_traces} keyspace and can be
 * retrieve manually using the trace identifier (the one returned by
 * {@link #getTraceId}).
 * <p>
 * This class provides facilities to fetch the traces from Cassandra. Please
 * note that the writing of the trace is done asynchronously in Cassandra. So
 * accessing the trace too soon after the query may result in the trace being
 * incomplete.
 */
public class QueryTrace {
    private static final String SELECT_SESSIONS_FORMAT = "SELECT * FROM system_traces.sessions WHERE session_id = %s";
    private static final String SELECT_EVENTS_FORMAT = "SELECT * FROM system_traces.events WHERE session_id = %s";

    private static final int MAX_TRIES = 5;
    private static final long BASE_SLEEP_BETWEEN_TRIES_IN_MS = 3;

    private final UUID traceId;

    private volatile String requestType;
    // We use the duration to figure out if the trace is complete, because
    // that's the last event that is written (and it is written asynchronously
    // so it's possible that a fetch gets all the trace except the duration).
    private volatile int duration = Integer.MIN_VALUE;
    private volatile InetAddress coordinator;
    private volatile Map<String, String> parameters;
    private volatile long startedAt;
    private volatile List<Event> events;

    private final SessionManager session;
    private final Lock fetchLock = new ReentrantLock();

    QueryTrace(UUID traceId, SessionManager session) {
        this.traceId = traceId;
        this.session = session;
    }

    /**
     * Returns the identifier of this trace.
     * <p>
     * Note that contrary to the other methods in this class, this
     * does not entail fetching query trace details from Cassandra.
     *
     * @return the identifier of this trace.
     */
    public UUID getTraceId() {
        return traceId;
    }

    /**
     * Returns the type of request.
     *
     * @return the type of request or {@code null} if the request
     * type is not yet available.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public String getRequestType() {
        maybeFetchTrace();
        return requestType;
    }

    /**
     * Returns the server-side duration of the query in microseconds.
     *
     * @return the (server side) duration of the query in microseconds. This
     * method will return {@code Integer.MIN_VALUE} if the duration is not yet
     * available.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public int getDurationMicros() {
        maybeFetchTrace();
        return duration;
    }

    /**
     * Returns the coordinator host of the query.
     *
     * @return the coordinator host of the query or {@code null}
     * if the coordinator is not yet available.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public InetAddress getCoordinator() {
        maybeFetchTrace();
        return coordinator;
    }

    /**
     * Returns the parameters attached to this trace.
     *
     * @return the parameters attached to this trace. or
     * {@code null} if the coordinator is not yet available.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public Map<String, String> getParameters() {
        maybeFetchTrace();
        return parameters;
    }

    /**
     * Returns the server-side timestamp of the start of this query.
     *
     * @return the server side timestamp of the start of this query or
     * 0 if the start timestamp is not available.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public long getStartedAt() {
        maybeFetchTrace();
        return startedAt;
    }

    /**
     * Returns the events contained in this trace.
     * <p>
     * Query tracing is asynchronous in Cassandra. Hence, it
     * is possible for the list returned to be missing some events for some of
     * the replica involved in the query if the query trace is requested just
     * after the return of the query it is a trace of (the only guarantee being
     * that the list will contain the events pertaining to the coordinator of
     * the query).
     *
     * @return the events contained in this trace.
     *
     * @throws TraceRetrievalException if the trace details cannot be retrieve
     * from Cassandra successfully.
     */
    public List<Event> getEvents() {
        maybeFetchTrace();
        return events;
    }

    @Override
    public String toString() {
        maybeFetchTrace();
        return String.format("%s [%s] - %dµs", requestType, traceId, duration);
    }

    private void maybeFetchTrace() {
        if (duration != Integer.MIN_VALUE)
            return;

        fetchLock.lock();
        try {
            doFetchTrace();
        } finally {
            fetchLock.unlock();
        }
    }

    private void doFetchTrace() {
        int tries = 0;
        try {
            // We cannot guarantee the trace is complete. But we can't at least wait until we have all the information
            // the coordinator log in the trace. Since the duration is the last thing the coordinator log, that's
            // what we check to know if the trace is "complete" (again, it may not contain the log of replicas).
            while (duration == Integer.MIN_VALUE && tries <= MAX_TRIES) {
                ++tries;

                ResultSetFuture sessionsFuture = session.executeQuery(new Requests.Query(String.format(SELECT_SESSIONS_FORMAT, traceId)), Statement.DEFAULT);
                ResultSetFuture eventsFuture = session.executeQuery(new Requests.Query(String.format(SELECT_EVENTS_FORMAT, traceId)), Statement.DEFAULT);

                Row sessRow = sessionsFuture.get().one();
                if (sessRow != null && !sessRow.isNull("duration")) {

                    requestType = sessRow.getString("request");
                    coordinator = sessRow.getInet("coordinator");
                    if (!sessRow.isNull("parameters"))
                        parameters = Collections.unmodifiableMap(sessRow.getMap("parameters", String.class, String.class));
                    startedAt = sessRow.getTimestamp("started_at").getTime();

                    events = new ArrayList<Event>();
                    for (Row evRow : eventsFuture.get()) {
                        events.add(new Event(evRow.getString("activity"),
                                    evRow.getUUID("event_id").timestamp(),
                                    evRow.getInet("source"),
                                    evRow.getInt("source_elapsed"),
                                    evRow.getString("thread")));
                    }
                    events = Collections.unmodifiableList(events);

                    // Set the duration last as it's our test to know if the trace is complete
                    duration = sessRow.getInt("duration");
                } else {
                    // The trace is not ready. Give it a few milliseconds before trying again.
                    // Notes: granted, sleeping uninterruptibly is bad, but  having all method propagate
                    // InterruptedException bothers me.
                    Uninterruptibles.sleepUninterruptibly(tries * BASE_SLEEP_BETWEEN_TRIES_IN_MS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            throw new TraceRetrievalException("Unexpected exception while fetching query trace", e);
        }

        if (tries > MAX_TRIES)
            throw new TraceRetrievalException(String.format("Unable to retrieve complete query trace for id %s after %d tries", traceId, MAX_TRIES));
    }

    /**
     * A trace event.
     * <p>
     * A query trace is composed of a list of trace events.
     */
    public static class Event {
        private final String name;
        private final long timestamp;
        private final InetAddress source;
        private final int sourceElapsed;
        private final String threadName;

        private Event(String name, long timestamp, InetAddress source, int sourceElapsed, String threadName) {
            this.name = name;
            // Convert the UUID timestamp to an epoch timestamp; I stole this seemingly random value from cqlsh, hopefully it's correct.
            this.timestamp = (timestamp - 0x01b21dd213814000L) / 10000;
            this.source = source;
            this.sourceElapsed = sourceElapsed;
            this.threadName = threadName;
        }

        /**
         * The event description, that is which activity this event correspond to.
         *
         * @return the event description.
         */
        public String getDescription() {
            return name;
        }

        /**
         * Returns the server side timestamp of the event.
         *
         * @return the server side timestamp of the event.
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * Returns the address of the host having generated this event.
         *
         * @return the address of the host having generated this event.
         */
        public InetAddress getSource() {
            return source;
        }

        /**
         * Returns the number of microseconds elapsed on the source when this event
         * occurred since when the source started handling the query.
         *
         * @return the elapsed time on the source host when that event happened
         * in microseconds.
         */
        public int getSourceElapsedMicros() {
            return sourceElapsed;
        }

        /**
         * Returns the name of the thread on which this event occurred.
         *
         * @return the name of the thread on which this event occurred.
         */
        public String getThreadName() {
            return threadName;
        }

        @Override
        public String toString() {
            return String.format("%s on %s[%s] at %s", name, source, threadName, new Date(timestamp));
        }
    }
}
