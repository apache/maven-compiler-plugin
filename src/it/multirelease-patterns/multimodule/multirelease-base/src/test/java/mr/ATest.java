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
package mr;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ATest {

    private static final String javaVersion = System.getProperty("java.version");

    @Test
    public void testGet8() throws Exception {
        //        assumeThat( javaVersion, is( "8" ) );

        assertEquals("BASE -> 8", A.getString());

        assertEquals("java.time.LocalDateTime", new A().introducedClass().getName());
    }

    @Test
    @Disabled("Maven module only creates Java 8 classes")
    public void testGet9() throws Exception {
        assumeTrue("9".equals(javaVersion));

        assertEquals("BASE -> 9", A.getString());

        assertEquals("java.lang.Module", new A().introducedClass().getName());
    }
}
