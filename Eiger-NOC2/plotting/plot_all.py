import os
from multiprocessing import Pool

def plot(experiment, path):
    os.system("python3 plot.py --dir " + path + " --experiment " + experiment)

dirs = {
    "freshness" :         "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/freshness-2023-04-05-09-32-39.csv",
    "threads" :           "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/num_clients-2023-04-05-16-40-00.csv",
    "num_servers" :       "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/num_servers-2023-04-05-20-52-39.csv",
    "read_prop" :         "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/read_prop-2023-04-06-02-19-04.csv",
    "txn_size" :          "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/tlen.csv",
    "value_size" :        "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/value_size-2023-04-06-16-00-08.csv",
    "distribution" :      "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/zipf-2023-04-11-08-18-38.csv",
    "num_key" :           "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/num_keys-2023-04-11-19-03-39.csv",
    "freshness_vs_zipf" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/zipf_fresh.csv",
}

def change_parameters(plus, normalize, normalizer = "EIGER_PORT"):
    cmd = f"sed -i \"s/^plus = .*/plus = {plus}/\" parameters.py ; sed -i \"s/^normalize = .*/normalize = {normalize}/\" parameters.py ; sed -i \"s/^normalizer = .*/normalizer = \"{normalizer}\"/\" parameters.py"
    os.system(cmd)
def plot_all(directories):
    with Pool(processes=len(directories)) as pool:
        pool.starmap(plot, directories.items())


if __name__ == "__main__":
    #print normalized
    change_parameters(plus = False, normalize = True, normalizer = "EIGER_PORT")
    plot_all(dirs)
    #print all except EP+
    change_parameters(plus = False, normalize = False, normalizer = "EIGER_PORT")
    plot_all(dirs)
    #print all
    change_parameters(plus = True, normalize = False, normalizer = "EIGER_PORT")
    plot_all(dirs)
    change_parameters(plus = False, normalize = True, normalizer = "EIGER_PORT_PLUS")
    plot_all(dirs)
    change_parameters(plus = True, normalize = True, normalizer = "EIGER_PORT")
    plot_all(dirs)