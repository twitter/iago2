#!/bin/sh
#
# Aurora mode web loadtest script.
#
# Note that in this example it depends on zookeeper to manage the cluster.
# Also check out "iago2/src/main/resources/iago.aurora" which is used by this example.
# "iago.aurora" is an Aurora config template. You need to replace certain commands with the real commands in "iago.aurora" (see the comments in that file) in order for this example to work.

THIS_DIR=$(cd $(dirname $0); pwd)
BASE_DIR=$(dirname $(dirname $THIS_DIR))

# REPLACE the values of the following variables with real values

export CLUSTER=mycluster
export ROLE=myrole
export ENV=myenv
export JOB_NAME=iago2_web_loadtest
export JVM_CMD="/path/to/bin/java -cp ./iago2-web-package-dist.jar"
export ZK_HOST=zookeeper.host
export ZK_PORT=2181
export ZK_NODE=/path/to/${ROLE}/${ENV}/parrot_server_${JOB_NAME}

java -cp ${BASE_DIR}/target/iago2-web-package-dist.jar \
  com.twitter.iago.launcher.Main \
  launch \
  -env.aurora \
  -env.aurora.cluster=${CLUSTER} \
  -env.aurora.role=${ROLE} \
  -env.aurora.env=${ENV} \
  -env.aurora.maxPerHost=1 \
  -env.aurora.config=${BASE_DIR}/../../src/main/resources/iago.aurora \
  -env.aurora.noKillBeforeLaunch=false \
  -env.aurora.feederNumInstances=1 \
  -env.aurora.feederNumCpus=1 \
  -env.aurora.feederRamInBytes=2000000000 \
  -env.aurora.feederDiskInBytes=2000000000 \
  -numInstances=1 \
  -env.aurora.serverNumCpus=1 \
  -env.aurora.serverRamInBytes=2000000000 \
  -env.aurora.serverDiskInBytes=2000000000 \
  -env.aurora.jvmCmd="${JVM_CMD}" \
  -zkHostName=${ZK_HOST} \
  -zkPort=${ZK_PORT} \
  -zkNode=${ZK_NODE} \
  -requestRate=1 \
  -duration=5.minutes \
  -inputLog=./replay.log \
  -reuseFile=true \
  -transportScheme=http \
  -victimHostPort="www.google.com:80" \
  -config=com.twitter.example.WebLoadTestConfig \
  -jobName=${JOB_NAME} \
  -yes
