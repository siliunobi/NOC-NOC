import os
import csv

def create_zipf_csv_files(input_file, output_folder):
    rows = []
    zipfs = set()

    with open(input_file, 'r') as file:
        reader = csv.DictReader(file)
        for row in reader:
            zipfs.add(row['zipfian_constant'])
            rows.append(row)

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    for zipf in zipfs:
        zipf_file_path = os.path.join(output_folder, f'{zipf}.csv')
        with open(zipf_file_path, 'w', newline='') as zipf_file:
            writer = csv.DictWriter(zipf_file, fieldnames=reader.fieldnames)
            writer.writeheader()
            for row in rows:
                if row['zipfian_constant'] == zipf:
                    writer.writerow(row)

def create_algorithm_csv_files(input_file, output_folder):
    rows = []
    algorithms = set()

    with open(input_file, 'r') as file:
        reader = csv.DictReader(file)
        for row in reader:
            algorithms.add(row['algorithm'])
            rows.append(row)

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    for algorithm in algorithms:
        algorithm_file_path = os.path.join(output_folder, f'{algorithm}.csv')
        with open(algorithm_file_path, 'w', newline='') as algorithm_file:
            writer = csv.DictWriter(algorithm_file, fieldnames=reader.fieldnames)
            writer.writeheader()
            for row in rows:
                if row['algorithm'] == algorithm:
                    # divide throughput by txn_size to get it in txn per second
                    if 'throughput' in row:
                        row['throughput'] = float(row['throughput']) / float(row['txn_size'])
                    writer.writerow(row)


def create_normalized_csv_files(input_file, output_folder):
    rows = []
    algorithms = set()
    eiger_port_values = {}

    with open(input_file, 'r') as file:
        reader = csv.DictReader(file)
        for row in reader:
            algorithms.add(row['algorithm'])
            rows.append(row)
            if row['algorithm'] == 'EIGER_PORT':
                # use ["algorithm","threads","read_prop","value_size","txn_size","num_clients","num_servers","num_key","distribution","zipfian_constant"] values as a key:
                key = tuple([row[key] for key in ["threads","read_prop","value_size","txn_size","num_clients","num_servers","num_key","zipfian_constant"]])
                # add throughput,average_latency,read_latency,write_latency,99th_latency,95th_latency as values
                eiger_port_values[key] = {
                    "throughput": row["throughput"],
                    "average_latency": row["average_latency"],
                    "read_latency": row["read_latency"],
                    "write_latency": row["write_latency"],
                    "99th_latency": row["99th_latency"],
                    "95th_latency": row["95th_latency"]
                }

    if not os.path.exists(output_folder):
        os.makedirs(output_folder)

    for algorithm in algorithms:
        algorithm_file_path = os.path.join(output_folder, f'{algorithm}.csv')
        with open(algorithm_file_path, 'w', newline='') as algorithm_file:
            writer = csv.DictWriter(algorithm_file, fieldnames=reader.fieldnames)
            writer.writeheader()
            for row in rows:
                if row['algorithm'] == algorithm:
                    normalized_row = normalize_row(row, eiger_port_values)
                    #print(normalized_row)
                    writer.writerow(normalized_row)

def normalize_row(row, eiger_port_values):
    normalized_row = row.copy()
    eiger_port = eiger_port_values[tuple([row[key] for key in ["threads","read_prop","value_size","txn_size","num_clients","num_servers","num_key","zipfian_constant"]])]
    for key in normalized_row.keys():
        if key not in ["algorithm","threads","read_prop","value_size","txn_size","num_clients","num_servers","num_key","distribution","zipfian_constant"]:
            # normalize each of throughput,average_latency,read_latency,write_latency,99th_latency,95th_latency
            if float(eiger_port[key]) == 0:
                normalized_row[key] = 0
            else:
                normalized_row[key] = float(normalized_row[key]) / float(eiger_port[key])
    return normalized_row

wanted_seconds = ["10","30","50","100","500","3000"]

def transform_csv_file(filename):
    # Read the entire file into memory
    with open(filename, "r") as file:
        data = list(csv.reader(file))
    
    # Extract the header
    header = data[0]
    
    # Modify the data in memory
    header_row = header[:10] + ["num_seconds", "staleness"]
    seconds = header[10:]
    modified_data = [header_row]
    
    for row in data[1:]:
        algorithm = row[0]
        remaining_data = row[10:]
        
        for second, staleness in zip(seconds, remaining_data):
            if(second not in wanted_seconds):
                continue
            new_row = [algorithm] + row[1:10] + [second, staleness]
            modified_data.append(new_row)
    
    # Write the modified data back to the file
    with open(filename, "w", newline="") as file:
        writer = csv.writer(file)
        writer.writerows(modified_data)



def split_all():
    paths = {
        "Freshness" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/freshness-2023-04-05-09-32-39.csv",
        "Num_Clients" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/num_clients-2023-04-05-16-40-00.csv",
        "Num_Servers" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/num_servers-2023-04-05-20-52-39.csv",
        "Read_Proportion" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/read_prop-2023-04-06-02-19-04.csv",
        "Transaction_Length" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/tlen.csv",
        "Zipf" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/zipf-2023-04-11-08-18-38.csv",
        "Value_Size" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/value_size-2023-04-06-16-00-08.csv",
        "Zipf_Fresh" : "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/zipf_fresh.csv",
    }

    freshPaths = ["/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Freshness/EIGER_PORT_PLUS_PLUS.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Freshness/EIGER_PORT_PLUS.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Freshness/EIGER_PORT.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Freshness/EIGER.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Zipf_Fresh/EIGER_PORT_PLUS_PLUS.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Zipf_Fresh/EIGER_PORT_PLUS.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Zipf_Fresh/EIGER_PORT.csv",
                  "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Zipf_Fresh/EIGER.csv",
                  ]
    for name, path in paths.items():
        print(name)
        create_algorithm_csv_files(path, f'/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/{name}')
        #create_normalized_csv_files(path, f'/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Normalized{name}')
    for path in freshPaths:
        transform_csv_file(path)
        continue

    for path in freshPaths[4:]:
        output_folder = path.split("/")[-1].split(".")[0]
        create_zipf_csv_files(path, f'/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/results/Zipf_Fresh/{output_folder}')

if __name__ == '__main__':
    split_all()