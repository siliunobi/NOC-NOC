import csv
import os

def append_transactions_to_csv(log_file_path, csv_file_path):
    with open(log_file_path, 'r') as log_file:
        with open(csv_file_path, 'a', newline='') as csv_file:
            writer = csv.writer(csv_file)
            
            # Skip the first line if it's a header
            if csv_file.tell() == 0:
                writer.writerow(['transaction_id', 'type', 'client_id', 'key', 'timestamp'])
            
            for line in log_file:
                if "Transaction = " in line:
                    # Extract the transaction information
                    transaction_info = line.split("Transaction = ")[1].strip()
                    transaction_data = transaction_info.split(',')
                    
                    # Create a transaction object
                    transaction_id = transaction_data[0]
                    transaction_type = transaction_data[1]
                    client_id = transaction_data[2]
                    key = transaction_data[3]
                    timestamp = transaction_data[4]
                    
                    # Write the transaction to the CSV file
                    writer.writerow([transaction_id, transaction_type, client_id, key, timestamp])


def process_tester(dir_name, output_file):
    # create output file and print header
    with open(output_file, 'w', newline='') as csv_file:
        writer = csv.writer(csv_file)
        writer.writerow(['transaction_id', 'type', 'client_id', 'key', 'timestamp'])

    for g in os.listdir(dir_name):
        if g.find("S") == -1:
            continue
        g = dir_name + '/' + g
        s = g.split("/S")[1]
        append_transactions_to_csv(g + '/server-0.log', output_file)

if __name__ == "__main__":
    # get dir_name and output_file as input using parser
    import argparse
    parser = argparse.ArgumentParser(description='Process server logs.')
    parser.add_argument('directory', metavar='directory', type=str, help='directory name')
    parser.add_argument('output_file', metavar='output_file', type=str, help='output file name')
    args = parser.parse_args()
    process_tester(args.directory, args.output_file)