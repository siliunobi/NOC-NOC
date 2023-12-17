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