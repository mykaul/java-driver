/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (C) 2020 ScyllaDB
 *
 * Modified by ScyllaDB
 */
package com.datastax.oss.driver.internal.core.metadata.token;

import net.jcip.annotations.Immutable;

/** A token generated by {@code Murmur3Partitioner}. */
@Immutable
public class Murmur3Token extends TokenLong64 {

  public Murmur3Token(long value) {
    super(value);
  }

  @Override
  public String toString() {
    return "Murmur3Token(" + value + ")";
  }
}
