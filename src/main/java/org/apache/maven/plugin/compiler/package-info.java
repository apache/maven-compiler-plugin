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

/**
 * Maven Compiler Plugin <abbr>MOJO</abbr>.
 * The {@link org.apache.maven.plugin.compiler.CompilerMojo}
 * and {@link org.apache.maven.plugin.compiler.TestCompilerMojo}
 * classes contain the configuration for compiling the main source code and the tests respectively.
 * These classes are mutable as they can be extended and have their properties modified in subclasses.
 * However, the actual compilation is performed by {@link org.apache.maven.plugin.compiler.ToolExecutor},
 * which takes a snapshot of the <abbr>MOJO</abbr> at construction time. After the test executor has been
 * created, it can be executed safely in a background thread even if the <abbr>MOJO</abbr> is modified concurrently.
 */
package org.apache.maven.plugin.compiler;
