# Usage

This source set is used to store all the code that IDE will invoke directly.
Which means, these code cannot be hot updated.

All external calls can only be made through [IJuggManagerCaller.kt](java/com/sickworm/intellij/jugg/ide/entry/IJuggManagerCaller.kt)