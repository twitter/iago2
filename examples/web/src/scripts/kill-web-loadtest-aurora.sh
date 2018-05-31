#!/bin/sh

THIS_DIR=$(cd $(dirname $0); pwd)
BASE_DIR=$(dirname $(dirname $THIS_DIR))

# REPLACE the values of the following variables with real values

export CLUSTER=mycluster
export ROLE=myrole
export ENV=myenv
export JOB_NAME=iago2_web_loadtest

java -cp ${BASE_DIR}/target/iago2-web-package-dist.jar \
  com.twitter.iago.launcher.Main \
  kill \
  -env.aurora \
  -env.aurora.cluster=${CLUSTER} \
  -env.aurora.role=${ROLE} \
  -env.aurora.env=${ENV} \
  -jobName=${JOB_NAME}
