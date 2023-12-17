# Common helper functions

import subprocess
from os import system

def get_prep_gc(algo, opw, read_prop, num_s = 5):
    gc_prep = 4000
    if algo == "KEY_LIST" and opw == 1:
        if read_prop >= 0.7:
            gc_prep*= 4
        else:
            gc_prep *= 1
    elif algo == "TIMESTAMP" and opw == 1:
        gc_prep *= 1
    elif algo == "CONST_ORT":
        gc_prep *= 1
    elif algo == "LORA":
        gc_prep *= (2*num_s)//5
    return gc_prep

def run_cmd(hosts, cmd, num, user="ubuntu", time=1000):
    cmd = "head -n %d /home/ubuntu/hosts/%s.txt > tmp.txt && parallel-ssh -t %d -O StrictHostKeyChecking=no -l %s -h tmp.txt \"%s\" && rm tmp.txt" % (num, hosts, time, user, cmd)
    if time != 1000:
        cmd = "timeout %d %s" % (time, cmd)
    print(cmd)
    system(cmd)

def run_cmd_single(host, cmd, user="ubuntu", time = None):
    cmd = "ssh -o StrictHostKeyChecking=no %s@%s \"%s\"" % (user, host, cmd)
    if time:
        cmd = "timeout %d %s" % (time, cmd)
    print(cmd)
    system(cmd)

def run_cmd_single_bg(host, cmd, user="ubuntu", time = None):
    cmd = "ssh -o StrictHostKeyChecking=no %s@%s \"%s\" &" % (user, host, cmd)
    print(cmd)
    system(cmd)


def start_cmd_disown(host, cmd, user="ubuntu"):
    run_cmd_single_bg(host, cmd+" & disown", user)


def start_cmd_disown_nobg(host, cmd, user="ubuntu"):
    run_cmd_single_bg(host, cmd+" disown", user)

def run_process_single(host, cmd, user="ubuntu", stdout=None, stderr=None):
    subprocess.call("ssh %s@%s \"%s\"" % ( user, host, cmd),
                    stdout=stdout, stderr=stderr, shell=True)

def upload_file(hosts, local_path, remote_path, user="ubuntu"):
    system("cp %s /tmp" % (local_path))
    script = local_path.split("/")[-1]
    system("parallel-scp -O StrictHostKeyChecking=no -l %s -h /home/ubuntu/hosts/%s.txt /tmp/%s %s" % (user, hosts, script, remote_path))

def run_script(hosts, script, user="ubuntu"):
    upload_file(hosts, script.split(" ")[0], "/tmp", user)
    run_cmd(hosts, "bash /tmp/%s" % (script.split("/")[-1]), user)

def fetch_file_single(host, remote, local, user="ubuntu"):
    system("scp -o StrictHostKeyChecking=no %s@%s:%s '%s'" % (user, host, remote, local))

def fetch_file_single_compressed(host, remote, local, user="ubuntu"):
    system("scp -o StrictHostKeyChecking=no %s@%s:%s '%s'" % (user, host, remote, local))

def fetch_file_single_compressed_bg(host, remote, local, user="ubuntu"):
    system("scp -o StrictHostKeyChecking=no %s@%s:%s '%s' &" % (user, host, remote, local))

def get_host_ips(hosts):
    return open("hosts/%s.txt" % (hosts)).read().split('\n')[:-1]
        
def sed(file, find, repl):
    iOpt = ''
    print('sed -i -e %s \'s/%s/%s/g\' %s' % (iOpt, escape(find), escape(repl), file))
    system('sed -i -e %s \'s/%s/%s/g\' %s' % (iOpt, escape(find), escape(repl), file))

def escape(path):
    return path.replace('/', '\/')

def get_node_ips():
    ret = []
    system("ec2-describe-instances > /tmp/instances.txt")
    system("ec2-describe-instances --region us-west-2 >> /tmp/instances.txt")
    for line in open("/tmp/instances.txt"):
        line = line.split()
        if line[0] != "INSTANCE" or line[5] != "running":
            continue
        # addr, externalip, internalip, ami
        ret.append((line[3], line[13], line[14], line[1]))
    return ret

def get_matching_ip(host, hosts):
    cips = get_host_ips(hosts)
    #argh should use a comprehension/filter; i'm tired
    for h in get_node_ips():
        if h[0] == host:
            return h[1]
