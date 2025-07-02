<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Compile Using Memory Allocation Enhancements

The Compiler Plugin accepts configurations for initial memory (`meminitial`)
and maximum memory (`maxmem`).
You can follow the example below to set the initial memory size to 128MB
and the maximum memory usage to 512MB:

```xml
<project>
  [...]
  <build>
    [...]
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>...</version>
        <configuration>
          <fork>true</fork>
          <meminitial>128m</meminitial>
          <maxmem>512m</maxmem>
        </configuration>
      </plugin>
    </plugins>
    [...]
  </build>
  [...]
</project>
```

Version 4 of the Maven compiler plugin additionally accepts the 'k', 'M' (upper-case)
and 'G' suffixes for kilobytes, megabytes and gigabytes respectively.
