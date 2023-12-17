import argparse
import os
import getpass
import pexpect
from multiprocessing import Pool

def setup_with_ssh(node, password):
    username = "ubuntu"
    child = pexpect.spawn(f"ssh-copy-id -o StrictHostKeyChecking=no {username}@{node}")
    i = child.expect([pexpect.TIMEOUT, 'password:'])
    if i == 0:
        print(f"Timeout when connecting to {node}")
    else:
        child.sendline(password)
        child.expect(pexpect.EOF)
        print(f"ssh-copy-id command executed on {node}")
    os.system(f"ssh -o StrictHostKeyChecking=no {username}@{node} \"sudo apt update ; sudo apt install -y default-jdk ; sudo apt install -y pssh ; sudo apt install -y maven \"")

def setup_no_ssh(node):
    username = "ubuntu"
    os.system(f"scp -prq /home/ubuntu/* {username}@{node}:/home/ubuntu/")

def setup(setup_ssh=False):
    file_name = "/home/ubuntu/hosts/all-hosts.txt"
    username = "ubuntu"
    # Read the contents of the file and store the nodes in a list
    with open(file_name, "r") as f:
        nodes = f.readlines()

    # Remove newline characters from the nodes
    nodes = [node.strip() for node in nodes]
    print("The nodes are: ", nodes)

    os.system("cd /home/ubuntu/kaiju ; mvn package")
    if setup_ssh:
        os.system("ssh-keygen -t rsa")
        password = getpass.getpass("Enter your password: ")
        with Pool(processes=len(nodes)) as pool:
            pool.starmap(setup_with_ssh, [(node, password) for node in nodes])
    else:
        with Pool(processes=len(nodes)) as pool:
            pool.starmap(setup_no_ssh, [(node,) for node in nodes])

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--setup_ssh", action="store_true",help="Setup ssh keys")
    args = parser.parse_args()
    setup(args.setup_ssh)
