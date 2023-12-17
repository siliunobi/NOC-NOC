#!/bin/bash

# Define the path to the experiments.py file
experiments_file="/home/ubuntu/kaiju/experiment/experiments.py"

# Define the path to the script for changing the constant value
change_constant_script="change_zipf.sh"

# Access the zipfian_constants array in experiments.py and call the script for each value
for value in $(grep -oP 'zipf_constants = \[\K[^\]]+' "$experiments_file" | tr ',' ' '); do
  bash "$change_constant_script" "$value"
  TAG="zipf"
  python3 setup_cluster.py
  python setup_hosts.py --color -c us-west-2 --experiment default --tag $TAG
done

bash "$change_constant_script" 0.8