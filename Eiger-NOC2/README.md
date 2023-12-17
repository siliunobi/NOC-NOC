
# EIGER-NOC2 Prototype

This repository contains the prototype for the EIGER-NOC2 project. It is based on the repository from the SIGMOD 2014 paper titled [Scalable Atomic Visibility with RAMP Transactions](http://www.bailis.org/papers/ramp-sigmod2014.pdf) and includes enhancements to run experiments on EIGER-PORT, EIGER-PORT+, and EIGER-NOC2 along with the enhancements provided by the RA-NOC2 repository. 

Most of the logic for the algorithms is located in the respective ServiceHandler files. For Eiger-NOC2 the logic is found in EigerPortPlusPlusKaijuServiceHandler.java. Some more logic is found in the MemoryStorageEngine.java and the respective Executor class, for Eiger-NOC2 it is called EigerPortPlusPlusExecutor, which handle a lot of the server-side logic.

## Setting up the CloudLab Cluster

1. Start an experiment with an OpenStack profile. 
2. Select a link speed of 1Gbps and the required number of nodes (clients + servers) using d710 from Emulab. Note that the results were calculated using eight servers and eight clients unless otherwise specified.
3. Once the CloudLab experiment is ready, access the OpenStack Dashboard as explained by CloudLab and create 16 nodes of size m1.large using the bionic-server image. Make sure to insert the following bash script in the configuration option:

```
#!bin/bash
sudo apt update
sudo apt install -y default-jdk
sudo apt install -y pssh
sudo apt install -y maven
sudo apt install -y python3-pip
pip3 install pexpect
```


4. Next, create one m1.medium instance, which we refer to as the Host and is the machine from which we will run our experiment. 
5. Assign a floating IP to the Host machine, copy the codebase to it, and SSH to it. The codebase should be copied so that in `/home/ubuntu` three folders appear:

- kaiju
- hosts
- results

You can use `scp -r ./* ubuntu@<IP-address>`

6. Access the `all-clients.txt` file and write the IP addresses of all client machines, the same for `all-servers.txt`. Finally, in `all-hosts.txt`, write all clients and all servers (servers first).

7. Now, change to the `/home/ubuntu/kaiju/experiment` folder and run `bash setup_cluster.sh`. (It will ask for a password to setup passwordless ssh among all the nodes, it's the same password that is used for the Openstack dahsboard)

For Replication experiments please double the number of servers you create.
## Running an Experiment

In `experiments.py`, you will find different experiments that you can run from the RAMP paper. You can expand and modify these experiments by altering the lists of parameters in the dictionary. For example, `default` is a test with default parameters. To run any experiment, change to the `/home/ubuntu/kaiju/experiment` folder and run:

```
python setup_hosts.py --color -c us-west-2 -nc NUM_CLIENTS -ns NUM_SERVERS --experiment EXP --tag example
```
Where `EXP` is the name of the experiment in `experiments.py`, and `NUM_CLIENTS` and `NUM_SERVERS` are the number of physical nodes, by default write 8 and 8.

Alternatively, you can run one of the existing experiments by running:
1. `bash run_default.sh`: runs a simple experiment with default parameters.
2. `bash run_number_clients.sh`: runs an experiment varying the number of client threads.
3. `bash run_number_servers.sh`: runs an experiment varying the number of servers (beware you need enough nodes for this).
4. `bash run_zipf_const.sh`: used to run experiment with zipfian constants, but edit the zipf in the Java code first (info in the script). 

and many others. You can also run all by doing `bash run_all.sh`,this will take several hours.

The logs will be uploaded to the `output` folder.

You can process the latest results by calling `bash process_latest_results.sh` or process a specific result by calling `python3 process_results.py "folder_name" "experiment_name"` and adding `--freshness` if you want to process data freshness and not latency/throughput.

## Further Questions

For further information on the codebase, please refer to the [RAMP GitHub repository](https://github.com/pbailis/ramp-sigmod2014-code).
