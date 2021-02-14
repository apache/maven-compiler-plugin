package example;

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

import java.util.Comparator;

/**
 * Example doclint comment.
 *
 * @since 3.8.2
 */
public class FinalExample 
{
    /**
     * Example field description.
     *
     * @return a comparator comparing two strings.
     */
    public static final Comparator<String> comparator = FinalExample::nullSafeStringComparator;

    /**
     * Example method description.
     * @param desc1 String to compare with desc2.
     * @param desc2 String to be compared agains desc1.
     * @return
     *      {@code -1} if desc1 is {@code null},
     *      {@code +1} if desc2 is {@code null} and desc1 is not,
     *      the comparison result if it is {!= 0},
     *      {@code 3} if the comparison result would have been {@code 0}.
     */
    public static int nullSafeStringComparator( String desc1, String desc2 )
    {
        final int compareTo;
        if ( desc1 == null )
        {
            compareTo = -1;
        }
        else if ( desc2 == null )
        {
            compareTo = 1;
        }
        else
        {
            compareTo = desc1.compareTo( desc2 );
        }
        if ( compareTo == 0 )
        {
            return 3;
        }
        return compareTo;
    }
}
