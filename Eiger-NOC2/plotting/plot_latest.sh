#!/bin/bash

# Get the latest folder name
latest_folder_name=$(ls -d /home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/* | sort -r | head -n 1)

# Get the experiment name from the user
read -p "Enter the experiment type: " exp_name

python3 plot.py --dir "$latest_folder_name" --experiment "$exp_name"