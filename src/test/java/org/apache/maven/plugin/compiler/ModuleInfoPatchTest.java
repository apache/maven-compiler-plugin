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
package org.apache.maven.plugin.compiler;

import javax.tools.OptionChecker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests {@link ModuleInfoPatch}.
 *
 * @author Martin Desruisseaux
 */
public class ModuleInfoPatchTest implements OptionChecker {
    /**
     * Test reading a file.
     *
     * @throws IOException if an I/O error occurred while loading the file
     */
    @Test
    public void testRead() throws IOException {
        var info = new ModuleInfoPatch(null, null);
        try (Reader r =
                new InputStreamReader(ModuleInfoPatchTest.class.getResourceAsStream("module-info-patch.maven"))) {
            info.load(r);
        }
        var config = new Options(this, Mockito.mock(Log.class));
        var out = new StringWriter();
        try (var buffered = new BufferedWriter(out)) {
            info.writeTo(config, buffered);
        }
        assertArrayEquals(
                new String[] {
                    "--add-modules",
                    "ALL-MODULE-PATH",
                    "--limit-modules",
                    "org.junit.jupiter.api",
                    "--add-reads",
                    "org.mymodule=org.junit.jupiter.api",
                    "--add-exports",
                    "org.mymodule/org.mypackage=org.someone,org.another",
                    "--add-exports",
                    "org.mymodule/org.foo=TEST-MODULE-PATH"
                },
                config.options.toArray());

        assertArrayEquals(
                new String[] {
                    "--add-modules ALL-MODULE-PATH",
                    "--limit-modules org.junit.jupiter.api",
                    "--add-reads org.mymodule=org.junit.jupiter.api",
                    "--add-exports org.mymodule/org.mypackage=org.someone,org.another",
                    "--add-exports org.mymodule/org.foo=TEST-MODULE-PATH",
                    "--add-opens org.mymodule/org.foo=org.junit.jupiter.api"
                },
                out.toString().split(System.lineSeparator()));
    }

    /**
     * {@return the number of arguments the given option takes}.
     *
     * @param option an option
     */
    @Override
    public int isSupportedOption(String option) {
        return 1;
    }
}
