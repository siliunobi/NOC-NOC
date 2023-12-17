#!/bin/bash

# Get the latest folder name
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)

# Get the experiment name from the user
read -p "Enter the experiment name: " exp_name

# Ask the user if the experiment measures data freshness
read -p "Does the experiment measure data freshness? [y/n]: " freshness

# Call the Python script with the latest folder name, experiment name, and --freshness flag if necessary
if [[ $freshness == [yY] ]]; then
  python3 process_results.py "$latest_folder_name" "$exp_name" --freshness
else
  python3 process_results.py "$latest_folder_name" "$exp_name"
fi