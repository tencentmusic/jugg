#!/bin/sh

# must run by run-as {packageName}

version=$1
arch=$2
instrumentsJarPath="/data/local/tmp/jugg/$version/jugg-instruments.jar"

if [ "$arch" = "ARCH_32_BIT" ]; then
  agentSoName="jugg_jvmti_agent_alt.so"
else
  agentSoName="jugg_jvmti_agent.so"
fi
agentSoOriginPath="/data/local/tmp/jugg/$version/$agentSoName"

# check file exists
if [ ! -f "$instrumentsJarPath" ]; then
  echo "jugg-instruments.jar not exists, push failed"
  exit 1
fi

if [ ! -f "$agentSoOriginPath" ]; then
  echo "jugg_jvmti_agent.so not exits, push failed"
  exit 1
fi

# push agent.so to startup_agents dir
agentDir="code_cache/startup_agents"
agentSoDestPath="$agentDir/$version-$agentSoName"
if [ -f "$agentSoDestPath" ]; then
  echo "$agentSoName already pushed"
else
  echo "need push $agentSoName"
  mkdir -p $agentDir
  # delete all old agents
  rm $agentDir/*-$agentSoName
  # push new agent
  cp $agentSoOriginPath $agentSoDestPath
fi


echo "push success"
