TAG="zipf"
# BEFORE RUNNING GO TO contrib/YCSB and change the constant to the required value
python3 setup_cluster.py
python setup_hosts.py --color -c us-west-2 --experiment default --tag $TAG