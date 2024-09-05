#!/bin/sh

# must run by run-as {packageName}

# greater=1, less=-1, equals=0, invalid=-2
compareVersion() {
    local regex="^[0-9]+\.[0-9]+\.?.*$"
	if ! echo "$1" | grep -qE "$regex" ; then
    echo "-2"
    return
  fi

  # get first two fields of version
  version1_major=$(echo $1 | cut -d'.' -f1)
  version1_minor=$(echo $1 | cut -d'.' -f2)

  version2_major=$(echo $2 | cut -d'.' -f1)
  version2_minor=$(echo $2 | cut -d'.' -f2)

  # 比较版本号的前两个字段
  if [[ $version1_major -gt $version2_major ]]; then
    echo "1"
  elif [[ $version1_major -lt $version2_major ]]; then
    echo "-1"
  else
    if [[ $version1_minor -gt $version2_minor ]]; then
      echo "1"
    elif [[ $version1_minor -lt $version2_minor ]]; then
      echo "-1"
    else
      echo "0"
    fi
  fi
}

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

  # auto setup fix flag for HarmonyOS
  harmonyOsVersion=$(getprop hw_sc.build.platform.version)
  # set need fix if >= 4.2
  compareResult=$(compareVersion "$harmonyOsVersion" "4.2")
  if [ $compareResult -eq 1 ] || [ $compareResult -eq 0 ] ; then
    echo "need auto setup fix"
    touch code_cache/.need_fix_dex_path_list
  else
    echo "no need auto setup fix"
  fi
fi


echo "push success"