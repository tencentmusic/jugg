#!/bin/sh

# e.g. path on device: /data/local/tmp/jugg/1.0/jugg_agent_setup.sh
# must run with run-as {packageName}

# cd to parent dir

version=$1
instrumentsJarPath="/data/local/tmp/jugg/$version/jugg-instruments.jar"
agentSoName="jugg_jvmti_agent.so"
agentSoOriginPath="/data/local/tmp/jugg/$version/$agentSoName"

# check file exists
if [ ! -f "$instrumentsJarPath" ]; then
  echo "jugg-instruments.jar not exists, push failed"
  exit 1
fi

if [ ! -f "$agentSoOriginPath"]; then
  echo "jugg_jvmti_agent.so not exits, push failed"
  exit 1
fi

# push agent.so to startup_agents dir
agentDir="code_cache/startup_agents"
agentSoDestPath="$agentDir/$version-$agentSoName"
if [ -f "$agentSoDestPath" ]; then
  echo "jugg_jvmti_agent.so already pushed"
else
  echo "need push jugg_jvmti_agent.so"
  mkdir -p $agentDir
  # delete all old agents
  rm "$agentDir/*-$agentSoName"
  # push new agent
  cp agentSoOriginPath $agentSoDestPath
fi

exit # exit run-as

echo "push success"