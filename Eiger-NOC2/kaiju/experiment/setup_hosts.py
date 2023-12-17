import argparse
from common_funcs import run_cmd
from common_funcs import run_cmd_single
from common_funcs import sed
from common_funcs import start_cmd_disown
from common_funcs import start_cmd_disown_nobg
from common_funcs import upload_file
from common_funcs import run_script
from common_funcs import fetch_file_single
from common_funcs import fetch_file_single_compressed
from common_funcs import fetch_file_single_compressed_bg
from threading import Thread, Lock
from experiments import *
import os
import itertools
import re
from datetime import datetime
from os import system 
from time import sleep
SERVERS_PER_HOST = 1
THRIFT_PORT = 8080
KAIJU_PORT=8081
KAIJU_HOSTS_INTERNAL=""
KAIJU_HOSTS_EXTERNAL=""
KAIJU_HOSTS_EXTERNAL_REPLICA=""
netCmd = "sudo sysctl net.ipv4.tcp_syncookies=1 > /dev/null; sudo sysctl net.core.netdev_max_backlog=250000 > /dev/null; sudo ifconfig ens3 txqueuelen 10000000; sudo sysctl net.core.somaxconn=100000 > /dev/null ; sudo sysctl net.core.netdev_max_backlog=10000000 > /dev/null; sudo sysctl net.ipv4.tcp_max_syn_backlog=1000000 > /dev/null; sudo sysctl -w net.ipv4.ip_local_port_range='1024 64000' > /dev/null; sudo sysctl -w net.ipv4.tcp_fin_timeout=2 > /dev/null; "

username = "ubuntu"

file_name_clients = "/home/ubuntu/hosts/all-clients.txt"
file_name_servers = "/home/ubuntu/hosts/all-servers.txt"
# Read the contents of the file and store the nodes in a list
with open(file_name_clients, "r") as f:
    nodes = f.readlines()
clients_list = [node.strip() for node in nodes]

with open(file_name_servers, "r") as f:
    nodes = f.readlines()
server_list = [node.strip() for node in nodes]
# Remove newline characters from the nodes
nodes = [node.strip() for node in nodes]

n_servers = len(clients_list)
n_clients = len(server_list)
is_replicated = False

def get_zipf():
    ZIPFIAN_CONSTANT = 0
    with open('/home/ubuntu/kaiju/contrib/YCSB/core/src/main/java/com/yahoo/ycsb/generator/ZipfianGenerator.java', 'r') as file:
        for line in file:
            match = re.search(r'ZIPFIAN_CONSTANT\s*=\s*([\d\.]+);', line)
            if match:
                value = match.group(1)
                ZIPFIAN_CONSTANT = round(float(value), 2)
                break
    return ZIPFIAN_CONSTANT

def start_servers(**kwargs):
    HEADER = "pkill -9 java; cd /home/ubuntu/kaiju/; rm *.log;"
    HEADER += netCmd
    baseCmd = "java -XX:+UseParallelGC  \
     -Djava.library.path=/usr/local/lib \
     -Dlog4j.configuration=file:/home/ubuntu/kaiju/src/main/resources/log4j.properties \
     -jar target/kaiju-1.0-SNAPSHOT.jar \
     -bootstrap_time %d \
     -kaiju_port %d \
     -id %d \
     -cluster %s \
     -thrift_port %d \
     -isolation_level %s \
     -ra_algorithm %s \
     -metrics_console_rate %d \
     -bloom-filter-ne %d \
     -max_object_size %d \
     -drop_commit_pct %f \
     -check_commit_delay_ms %d\
     -outbound_internal_conn %d \
     -locktable_numlatches %d \
     -tester %d \
     -opw %d \
     -replication %d \
     -freshness_test %d \
      1>server-%d.log 2>&1 & "
    setup_hosts()
    sid = 0
    i = 0
    replication = kwargs.get("replication",0)
    num_s = n_servers
    if replication == 1:
        num_s *= 2
    for server in server_list:
        if i == num_s:
            break
        servercmd = HEADER
        for s_localid in range(0, SERVERS_PER_HOST):
            servercmd += (
               baseCmd % (
                   kwargs.get("bootstrap_time_ms", 1000),
                   KAIJU_PORT+s_localid,
                   sid,
                   KAIJU_HOSTS_INTERNAL,
                   THRIFT_PORT+s_localid,
                   kwargs.get("isolation_level"),
                   kwargs.get("ra_algorithm"),
                   kwargs.get("metrics_printrate", -1),
                   kwargs.get("bloom_filter_numbits", 256),
                   max(16384, (100+kwargs.get("valuesize"))*kwargs.get("txnlen")+1000),
                   kwargs.get("drop_commit_pct", 0),
                   kwargs.get("check_commit_delay", -1),
                   kwargs.get("outbound_internal_conn", 1),
                   kwargs.get("locktable_numlatches", 1024),
                   kwargs.get("tester", 0),
                   kwargs.get("opw", 0),
                   kwargs.get("replication",0),
                   kwargs.get("freshness",0),
                   s_localid))
            sid += 1
        i += 1
        pprint("Starting kv-servers on [%s]" % server)
        start_cmd_disown_nobg(server, servercmd)

def setup_hosts():
    num_s = n_servers
    if is_replicated:
        num_s *= 2
    pprint("Appending authorized key...")
    run_cmd("all-hosts", "sudo chown ubuntu /etc/security/limits.conf; sudo chmod u+w /etc/security/limits.conf; sudo echo '* soft nofile 1000000\n* hard nofile 1000000' >> /etc/security/limits.conf; sudo chown ubuntu /etc/pam.d/common-session; sudo echo 'session required pam_limits.so' >> /etc/pam.d/common-session",num_s+n_clients)
    #run_cmd("all-hosts", "cat /home/ubuntu/.ssh/kaiju_rsa.pub >> /home/ubuntu/.ssh/authorized_keys", user="ubuntu")
    pprint("Done")

    run_cmd("all-hosts", " wget --output-document sigar.tar.gz 'http://downloads.sourceforge.net/project/sigar/sigar/1.6/hyperic-sigar-1.6.4.tar.gz?r=http%3A%2F%2Fsourceforge.net%2Fprojects%2Fsigar%2Ffiles%2Fsigar%2F1.6%2F&ts=1375479576&use_mirror=iweb'; tar -xvf sigar*; sudo rm /usr/local/lib/libsigar*; sudo cp ./hyperic-sigar-1.6.4/sigar-bin/lib/libsigar-amd64-linux.so /usr/local/lib/; rm -rf *sigar*",n_clients+num_s)
    run_cmd("all-hosts", "sudo echo 'include /usr/local/lib' >> /etc/ld.so.conf; sudo ldconfig",n_clients+num_s)

def fetch_logs(runid, clients, servers, **kwargs):
    def fetchYCSB(rundir, client):
        client_dir = rundir+"/"+"C"+client
        system("mkdir -p "+client_dir)
        fetch_file_single_compressed(client, "/home/ubuntu/kaiju/contrib/YCSB/*.log", client_dir)

    def fetchYCSBbg(rundir, client):
        client_dir = rundir+"/"+"C"+client
        system("mkdir -p "+client_dir)
        sleep(.1)
        fetch_file_single_compressed_bg(client, "/home/ubuntu/kaiju/contrib/YCSB/*.log", client_dir)

    def fetchkaiju(rundir, server, symbol):
        server_dir = rundir+"/"+symbol+server
        system("mkdir -p "+server_dir)
        fetch_file_single_compressed(server, "/home/ubuntu/kaiju/*.log", server_dir)

    def fetchkaijubg(rundir, server, symbol):
        server_dir = rundir+"/"+symbol+server
        system("mkdir -p "+server_dir)
        fetch_file_single_compressed_bg(server, "/home/ubuntu/kaiju/*.log", server_dir)

    outroot = args.output_dir+'/'+runid

    system("mkdir -p "+args.output_dir)

    bgfetch = kwargs.get("bgrun", False)

    ths = []
    pprint("Fetching YCSB logs from clients.")
    
    for i,client in enumerate(clients):
        if i == n_clients:
            break
        if not bgfetch:
            t = Thread(target=fetchYCSB, args=(outroot, client))
            t.start()
            ths.append(t)
        else:
            fetchYCSBbg(outroot,client)

    for th in ths:
        th.join()
    pprint("Done clients")

    ths = []
    num_s = n_servers
    if is_replicated:
        num_s *= 2
    pprint("Fetching logs from servers.")
    for i,server in enumerate(servers):
        if i == num_s:
            break
        if not bgfetch:
            t = Thread(target=fetchkaiju, args=(outroot, server, "S"))
            t.start()
            ths.append(t)

        else:
            fetchkaijubg(outroot, server, "S")

    for th in ths:
        th.join()
    pprint("Done")

    
    if bgfetch:
        sleep(30)
def run_cmd_in_kaiju(hosts, cmd, user='ubuntu'):
    n = 0
    num_s = n_servers
    if is_replicated:
        num_s *= 2
    if hosts == "all-clients":
        n = n_clients
    elif hosts == "all-servers":
        n = num_s
    elif hosts == "all-hosts":
        n = num_s+n_clients
    run_cmd(hosts, "cd /home/ubuntu/kaiju/; %s" % cmd, user, n)

def pprint(str):
    global USE_COLOR
    if USE_COLOR:
        print ('\033[94m%s\033[0m' % str)
    else:
        print (str)
def start_ycsb_clients(**kwargs):
    def fmt_ycsb_string(runType):
        hosts = ""
        if kwargs.get("replication",0) == 1:
            hosts = KAIJU_HOSTS_EXTERNAL_REPLICA
        else:
            hosts = KAIJU_HOSTS_EXTERNAL
        return (('cd /home/ubuntu/kaiju/contrib/YCSB;' +
                 netCmd+
                 'rm *.log;' \
                     'bin/ycsb %s kaiju -p hosts=%s -threads %d -p txnlen=%d -p readproportion=%s -p updateproportion=%s -p fieldlength=%d -p histogram.buckets=%d -p fieldcount=1 -p operationcount=100000000 -p recordcount=%d -p isolation_level=%s -p read_atomic_algorithm=%s -t -s -p requestdistribution=%s -p maxexecutiontime=%d -P %s' \
                     ' 1>%s_out.log 2>%s_err.log' 
                     )% (                              
                            runType,
                            hosts,
                                                      kwargs.get("threads", 10) if runType != 'load' else min(1000, kwargs.get("recordcount")/10),
                                                      kwargs.get("txnlen", 8),
                                                      kwargs.get("readprop", .5),
                                                      1-kwargs.get("readprop", .5),
                                                      kwargs.get("valuesize", 1),
                                                      kwargs.get("numbuckets", 10000),
                                                      kwargs.get("recordcount", 10000),
                                                      kwargs.get("isolation_level", "READ_ATOMIC"),
                                                      kwargs.get("ra_algorithm", "TIMESTAMP"),
                            kwargs.get("keydistribution", "zipfian"),
                            kwargs.get("time", 60) if runType != 'load' else 10000,
                            kwargs.get("workload", "workloads/workloada"),
                            runType,runType
                            )
                            )
    ip_client = clients_list[0]
    pprint("Loading YCSB on single client: %s." % (ip_client))
    run_cmd_single(ip_client, fmt_ycsb_string("load"), time=kwargs.get("recordcount", 180))
    pprint("Done")
    sleep(10)

    pprint("Running YCSB on all clients.")
    i = 0
    if kwargs.get("bgrun", False):
        for client in clients_list:
            if i == n_clients:
                break
            i += 1
            start_cmd_disown(client, fmt_ycsb_string("run"))

        sleep(kwargs.get("time")+15)
    else:
        run_cmd("all-clients", fmt_ycsb_string("run"), n_clients,time=kwargs.get("time", 60)+30)
    pprint("Done")


def run_ycsb_trial(tag, serverArgs="", **kwargs):
    pprint("Running trial %s" % kwargs.get("runid", "no label"))
    pprint("Restarting kaiju clusters %s" % tag)
    #if kwargs.get("killservers", True):
    start_servers(**kwargs)
    sleep(kwargs.get("bootstrap_time_ms", 1000)/1000.*2+5)
    #else:
    #stop_kaiju_clients(clusters)
    start_ycsb_clients(**kwargs)
    runid = kwargs.get("runid", str(datetime.now()).replace(' ', '_'))
    #print "KILLING JAVA"
    #run_cmd("all-servers", "pkill --signal SIGQUIT java")
    fetch_logs(runid, clients_list, server_list)

def jumpstart_hosts():
    pprint("Resetting git...")
    run_cmd_in_kaiju('all-hosts', 'git stash', user="ubuntu")
    pprint("Done")

run_opw_RAMP = False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Setup cassandra on EC2')
    parser.add_argument('--tag', dest='tag', required=True, help='Tag to use for your instances')
    parser.add_argument('--fetchlogs', '-f', action='store_true',
                        help='Fetch logs and exit')
    parser.add_argument('--launch', '-l', action='store_true',
                        help='Launch EC2 cluster')
    parser.add_argument('--claim', action='store_true',
                        help='Claim non-tagged instances as our own')
    parser.add_argument('--kill_num',
                        help='Kill specified number of instances',
                        default=-1,
                        type=int)
    parser.add_argument('--setup', '-s', action='store_true',
                        help='Set up already running EC2 cluster')
    parser.add_argument('--terminate', '-t', action='store_true',
                        help='Terminate the EC2 cluster')
    parser.add_argument('--restart', '-r', action='store_true',
                        help='Restart kaiju cluster')
    parser.add_argument('--rebuild', '-rb', action='store_true',
                        help='Rebuild kaiju cluster')
    parser.add_argument('--fetch', action='store_true',
                        help='Fetch logs')
    parser.add_argument('--rebuild_clients', '-rbc', action='store_true',
                        help='Rebuild kaiju clients')
    parser.add_argument('--rebuild_servers', '-rbs', action='store_true',
                        help='Rebuild kaiju servers')
    parser.add_argument('--num_servers', '-ns', dest='servers', nargs='?',
                        default=2, type=int,
                        help='Number of server machines per cluster, default=2')
    parser.add_argument('--num_clients', '-nc', dest='clients', nargs='?',
                        default=2, type=int,
                        help='Number of client machines per cluster, default=2')
    parser.add_argument('--output', dest='output_dir', nargs='?',
                        default="./output", type=str,
                        help='output directory for runs')
    parser.add_argument('--clusters', '-c', dest='clusters', nargs='?',
                        default="us-east-1", type=str,
                        help='List of clusters to start, command delimited, default=us-east-1:1')
    parser.add_argument('--no_spot', dest='no_spot', action='store_true',
                        help='Don\'t use spot instances, default off.')
    parser.add_argument('--color', dest='color', action='store_true',
                        help='Print with pretty colors, default off.')
    parser.add_argument('-D', dest='kaiju_args', action='append', default=[],
                     help='Parameters to pass along to the kaiju servers/clients.')

    parser.add_argument('--placement_group', dest='placement_group', default="KAIJUCLUSTER")

    parser.add_argument('--branch', dest='branch', default='master',
                        help='Parameters to pass along to the kaiju servers/clients.')

    parser.add_argument('--experiment', dest='experiment',
                     help='Named, pre-defined experiment.')

    parser.add_argument('--ycsb_vary_constants_experiment', action='store_true', help='run experiment for varying constants')

    args,unknown = parser.parse_known_args()

    USE_COLOR = args.color
    pprint("Reminder: Run this script from an ssh-agent!")
    kaijuArgString = ' '.join(['-D%s' % arg for arg in args.kaiju_args])
    if args.setup or args.launch:
        setup_hosts()
        jumpstart_hosts()
    if args.experiment:
        tag = args.tag
        experiment = experiments[args.experiment]

        args.output_dir=args.output_dir+"/"+args.experiment+"-"+str(datetime.now()).replace(" ", "-").replace(":","-").split(".")[0]

        system("mkdir -p "+args.output_dir)
        system("cp experiments.py "+args.output_dir)
        fresh = experiment["freshness"]
        tester = experiment["tester"]
        replication = experiment["replication"]
        if replication == 1:
            is_replicated = True
        for nc, ns in experiment["serversList"]:
            n_clients = nc
            n_servers = ns
            args.servers = ns
            args.clients = nc
            num_s = ns
            if replication == 1:
                num_s *= 2
                
            KAIJU_HOSTS_INTERNAL = None
            i = 0
            for server in server_list:
                if i == num_s:
                    break
                for loc_id in range (0,SERVERS_PER_HOST):
                    if KAIJU_HOSTS_INTERNAL:
                        KAIJU_HOSTS_INTERNAL += ","
                        KAIJU_HOSTS_EXTERNAL += ","
                        if i < num_s//2:
                            KAIJU_HOSTS_EXTERNAL_REPLICA += ","
                    else:
                        KAIJU_HOSTS_EXTERNAL = ""
                        KAIJU_HOSTS_EXTERNAL_REPLICA = ""
                        KAIJU_HOSTS_INTERNAL = ""
                    KAIJU_HOSTS_INTERNAL += server + ":" + str(KAIJU_PORT+loc_id)
                    KAIJU_HOSTS_EXTERNAL += server + ":" + str(THRIFT_PORT+loc_id)
                    if i < num_s//2:
                        KAIJU_HOSTS_EXTERNAL_REPLICA += server + ":" + str(THRIFT_PORT+loc_id)
                i += 1
            for iteration in experiment["iterations"]:
                firstrun = True
                for readprop in experiment["readprop"]:
                    for numkeys in experiment["numkeys"]:
                        for valuesize in experiment["valuesize"]:
                            for txnlen in experiment["txnlen"]:
                                for threads in experiment["threads"]:
                                    for drop_commit_pct in experiment["drop_commit_pcts"]:
                                        for check_commit_delay in experiment["check_commit_delays"]:
                                            for config in experiment["configs"]:
                                                for distribution in experiment["keydistribution"]:
                                                    if distribution == "zipfian":
                                                        zipf = get_zipf()
                                                    else:
                                                        zipf = 0.0
                                                    opw = 0
                                                    isolation_level = config
                                                    ra_algorithm = "KEY_LIST"
                                                    algo = config
                                                    if(config.find("READ_ATOMIC") != -1):
                                                        isolation_level = "READ_ATOMIC"
                                                        if(config == "READ_ATOMIC_LIST"):
                                                            ra_algorithm = "KEY_LIST"
                                                        elif(config == "READ_ATOMIC_BLOOM"):
                                                            ra_algorithm = "BLOOM_FILTER"
                                                        elif(config == "READ_ATOMIC_LORA"):
                                                            ra_algorithm = "LORA"
                                                        elif(config == "READ_ATOMIC_CONST_ORT"):
                                                            ra_algorithm = "CONST_ORT"
                                                        elif(config == "READ_ATOMIC_NOC"):
                                                            ra_algorithm = "NOC"
                                                        elif(config == "READ_ATOMIC_FASTOPW"):
                                                            ra_algorithm = "KEY_LIST"
                                                            opw = 1
                                                        elif(config == "READ_ATOMIC_SMALLOPW"):
                                                            ra_algorithm = "TIMESTAMP"
                                                            opw = 1
                                                        else:
                                                            ra_algorithm = "TIMESTAMP"
                                                    elif(config == "EIGER"):
                                                        algo = "EIGER"
                                                        ra_algorithm = "EIGER"
                                                    elif(config == "EIGER_PORT"):
                                                        algo = "EIGER_PORT"
                                                        ra_algorithm = "EIGER_PORT"
                                                        isolation_level = "EIGER"
                                                    elif(config == "EIGER_PORT_PLUS"):
                                                        algo = "EIGER_PORT_PLUS"
                                                        ra_algorithm = "EIGER_PORT_PLUS"
                                                        isolation_level = "EIGER"
                                                    elif(config == "EIGER_PORT_PLUS_PLUS"):
                                                        algo = "EIGER_PORT_PLUS_PLUS"
                                                        ra_algorithm = "EIGER_PORT_PLUS_PLUS"
                                                        isolation_level = "EIGER"

                                                    firstrun = True
                                                    run_ycsb_trial(tag, runid=("%s-%d-THREADS%d-RPROP%s-VS%d-TXN%d-NC%s-NS%s-NK%d-DCP%f-CCD%d-IT%d-KD%s-ZC%f" % (algo,
                                                                                                                                                    txnlen,
                                                                                                                                                threads,
                                                                                                                                                readprop,
                                                                                                                                                valuesize,
                                                                                                                                                txnlen,
                                                                                                                                                nc,
                                                                                                                                                ns,
                                                                                                                                                numkeys,
                                                                                                                                                drop_commit_pct,
                                                                                                                                                check_commit_delay,
                                                                                                                                                iteration,
                                                                                                                                                distribution,
                                                                                                                                                round(zipf,2)
                                                                                                                                                )),
                                                                bootstrap_time_ms=experiment["bootstrap_time_ms"],
                                                                threads=threads,
                                                                txnlen=txnlen,
                                                                readprop=readprop,
                                                                recordcount=numkeys,
                                                                time=experiment["numseconds"],
                                                                timeout=120*10000,
                                                                ra_algorithm = ra_algorithm,
                                                                isolation_level = isolation_level,
                                                                keydistribution=distribution,
                                                                valuesize=valuesize,
                                                                numbuckets=100,
                                                                metrics_printrate=-1,
                                                                killservers=firstrun,
                                                                drop_commit_pct=drop_commit_pct,
                                                                check_commit_delay=check_commit_delay,
                                                                bgrun=experiment["launch_in_bg"],
                                                                opw = opw,
                                                                replication=replication,
                                                                freshness=fresh,
                                                                tester=tester)
                                                    firstrun = False
