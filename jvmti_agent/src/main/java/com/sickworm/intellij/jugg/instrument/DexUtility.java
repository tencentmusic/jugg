/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sickworm.intellij.jugg.instrument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Creates the in-memory dex elements used by Apply Changes for new classes. */
public final class DexUtility {

    private DexUtility() {
    }

    /** Creates and appends the new in-memory dex elements after existing elements. */
    public static Object[] createNewDexElements(byte[][] dexByteArrays, Object[] oldElements)
            throws Exception {
        ByteBuffer[] dexFiles = new ByteBuffer[dexByteArrays.length];
        for (int i = 0; i < dexByteArrays.length; ++i) {
            dexFiles[i] = ByteBuffer.wrap(dexByteArrays[i], 0, dexByteArrays[i].length);
        }

        Object[] newElements = makeInMemoryDexElements(dexFiles, new ArrayList<>());
        if (newElements != null && newElements.length > 0) {
            Object[] dexElements = new Object[oldElements.length + newElements.length];
            System.arraycopy(oldElements, 0, dexElements, 0, oldElements.length);
            System.arraycopy(newElements, 0, dexElements, oldElements.length, newElements.length);
            return dexElements;
        }
        return oldElements;
    }

    public static native Object[] makeInMemoryDexElements(
            ByteBuffer[] dexFiles, List<IOException> suppressedExceptions);
}
