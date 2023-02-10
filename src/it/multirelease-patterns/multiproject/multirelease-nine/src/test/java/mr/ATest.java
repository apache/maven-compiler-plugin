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

import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

public class ATest {

    private static final String javaVersion = System.getProperty("java.version");

    @Test
    @Ignore("Maven module only creates Java 9 classes")
    public void testGet8() throws Exception {
        assumeThat(javaVersion, is("8"));

        assertThat(A.getString(), is("BASE -> 8"));

        assertThat(new A().introducedClass().getName(), is("java.time.LocalDateTime"));
    }

    @Test
    public void testGet9() throws Exception {
        //        assumeThat( javaVersion, is( "9" ) );

        assertThat(A.getString(), is("BASE -> 9"));

        assertThat(new A().introducedClass().getName(), is("java.lang.Module"));
    }
}
