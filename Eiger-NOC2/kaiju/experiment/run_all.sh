TAG="freshness"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment freshness --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" freshness --freshness
rm -r output/*

TAG="num_clients"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment num_clients --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" num_clients
rm -r output/*

TAG="num_servers"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment num_servers --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" num_servers
rm -r output/*

TAG="read_prop"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment read_prop --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" read_prop
rm -r output/*

TAG="txn_len"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment txn_len --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" txn_len
rm -r output/*

TAG="value_size"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment value_size --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" value_size
rm -r output/*


bash run_zipf_const.sh
mkdir output/zipf
find /home/ubuntu/kaiju/experiment/output/* -type d -maxdepth 0 -not -name zipf -exec sh -c 'mv "$1"/* /home/ubuntu/kaiju/experiment/output/zipf && rmdir "$1"' _ {} \;
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" distribution
rm -r output/*

bash run_freshness_zipf_const.sh
mkdir output/zipf
find /home/ubuntu/kaiju/experiment/output/* -type d -maxdepth 0 -not -name zipf -exec sh -c 'mv "$1"/* /home/ubuntu/kaiju/experiment/output/zipf && rmdir "$1"' _ {} \;
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" freshness_zipf
rm -r output/*

TAG="num_keys"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment num_keys --tag $TAG
latest_folder_name=$(ls -td /home/ubuntu/kaiju/experiment/output/*/ | head -n1)
python3 process_results.py "$latest_folder_name" num_keys
rm -r output/*