TAG="num_clients"
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment num_clients --tag $TAG
