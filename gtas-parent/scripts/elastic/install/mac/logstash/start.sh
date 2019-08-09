#!/bin/bash

parent_dir=$( cd "$(dirname "${BASH_SOURCE[0]}")" ; pwd -P )

cd "$parent_dir"

source ../set_env.sh

cd $ES_INSTALL_LOCATION

cd "logstash"

export ELASTICSEARCH_HOSTS='https://localhost:9200'
export SERVER_HOST='localhost'

# Start Elstic Search

./bin/logstash & echo $! > ./pid
