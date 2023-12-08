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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Show the modifications between two lists.
 */
final class DeltaList<E> {

    private final List<E> added;
    private final List<E> removed;
    private final boolean hasChanged;

    DeltaList(Collection<E> oldList, Collection<E> newList) {
        this.added = new ArrayList<>(newList);
        this.removed = new ArrayList<>(oldList);
        added.removeAll(oldList);
        removed.removeAll(newList);
        this.hasChanged = !added.isEmpty() || !removed.isEmpty();
    }

    Collection<E> getAdded() {
        return Collections.unmodifiableCollection(added);
    }

    Collection<E> getRemoved() {
        return Collections.unmodifiableCollection(removed);
    }

    boolean hasChanged() {
        return hasChanged;
    }
}
