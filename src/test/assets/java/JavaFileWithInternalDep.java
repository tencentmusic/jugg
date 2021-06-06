package com.sickworm.intellij.aidp.test;

import java.io.File;
import java.util.ArrayList;
import com.sickworm.intellij.aidp.CompileFileInfo;

public class JavaFileWithInternalDep {
    public static void main(String[] args) {
        System.out.println("Hello AIDP!");
        File javaFile = new File("src/test/assets/java/HelloWorldJavaFile.java");
        CompileFileInfo fileInfo = new CompileFileInfo(javaFile, CompileFileInfo.Type.JAVA, new ArrayList<>());
    }
}
