
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

import java.nio.file.*;
import java.time.*;
import java.time.temporal.*
import java.util.*;

File target = new File( basedir, "target" );
assert target.isDirectory()

File jarFile = new File( target, "MCOMPILER-525-no-recreation-1.0-SNAPSHOT.jar" );
assert jarFile.isFile()

File refFile = new File( target, "reference.jar" );
assert refFile.isFile()

Instant referenceTimestamp = Files.getLastModifiedTime( refFile.toPath() )
    .toInstant().truncatedTo( ChronoUnit.MILLIS );
System.out.println( "Reference timestamp: " + referenceTimestamp );

Instant actualTimestamp = Files.getLastModifiedTime( jarFile.toPath() )
    .toInstant().truncatedTo( ChronoUnit.MILLIS );
System.out.println( "Actual timestamp   : " + actualTimestamp );

assert referenceTimestamp.equals(actualTimestamp),
    "Timestamps don't match, JAR was recreated although contents has not changed"
