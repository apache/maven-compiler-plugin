/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package example;

import java.util.Comparator;

public class FinalExample {
    public static final Comparator<String> comparator = (o1, o2) -> {
        final String desc1 = o1;
        final String desc2 = o2;
        final int compareTo;
        if (desc1 == null) {
            compareTo = -1;
        } else if (desc2 == null) {
            compareTo = 1;
        } else {
            compareTo = desc1.compareTo(desc2);
        }
        if (compareTo == 0) {
            return 3;
        }
        return compareTo;
    };
}
