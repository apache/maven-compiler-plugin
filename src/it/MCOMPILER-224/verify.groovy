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
def log = new File( basedir, 'build.log').text

def noteExists = log.contains("[INFO] NOTE Test message.")
def otherExists =  log.contains("[INFO] OTHER Test message.")
def warningExists = log.contains("[WARNING] WARNING Test message.")
def mandatoryWarningExists = log.contains("[WARNING] MANDATORY_WARNING Test message.")

def fail = false
def messages = "The following assertions were violated:"
if ( !noteExists ){
    messages += "\nNOTE message not logged in INFO level!"
    fail = true
}

if ( !otherExists ){
    messages += "\nOTHER message not logged in INFO level!"
    fail = true
}

if ( !warningExists ){
    messages += "\nWARNING message not logged in WARNING level!"
    fail = true
}

if ( !mandatoryWarningExists ){
    messages += "\nMANDATORY_WARNING message not logged in WARNING level!"
    fail = true
}

if ( fail ){
    throw new RuntimeException( messages )
}

