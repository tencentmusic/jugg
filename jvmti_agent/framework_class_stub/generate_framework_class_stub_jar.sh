#!/bin/sh

cd "$(dirname "$0")"

mkdir -p build/target/classes
find src -name "*.java" -print0 | xargs -0 javac -d build/target/classes -sourcepath src
if [ $? -ne 0 ]; then
  echo "Compilation failed"
  exit -1
fi

cd build/target/classes
jar cf ../../../framework_class_stub.jar *
