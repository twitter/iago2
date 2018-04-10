/* Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */
namespace java com.twitter.iago.thriftjava
#@namespace scala com.twitter.iago.thriftscala
namespace rb Parrot

enum ParrotState {
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    SHUTDOWN,
    UNKNOWN
}

struct ParrotStatus {
    1: optional i32 linesProcessed;
    2: optional ParrotState status;
    3: optional double requestsPerSecond;
    4: optional double queueDepth;
    5: optional i32 totalProcessed;
}

struct ParrotLog { // pager & tailer interface
	1: i64 offset;
	2: i32 length;
	3: string data;
}

service ParrotServerService {
    void setRate(1: i32 newRate),
    ParrotStatus sendRequest(1: list<string> lines),
    ParrotStatus getStatus(),
    void start(),
    oneway void shutdown(),
    void pause(),
    void resume(),
    string fetchThreadStacks(),
    ParrotLog getLog(1: i64 offset, 2: i32 length, 3: string fileName)
}
