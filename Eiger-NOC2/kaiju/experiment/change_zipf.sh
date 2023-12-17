#!/bin/bash

# Check if the input value matches any of the allowed numbers
if ! [[ "$1" =~ ^(0(\.[0-9]*)?|0\.3|0\.7|0\.9|0\.99|1\.1|1\.2)$ ]]; then
  echo "Error: Invalid input value. Allowed values: 0, 0.3, 0.7, 0.9, 0.99, 1.1, 1.2"
  exit 1
fi

new_value="$1"
# replace ZIPFIAN_CONSTANT value in ScrambledZipfianGenerator.java file
sed -i "s/ZIPFIAN_CONSTANT=.*;/ZIPFIAN_CONSTANT=$new_value;/" /home/ubuntu/kaiju/contrib/YCSB/core/src/main/java/com/yahoo/ycsb/generator/ZipfianGenerator.java

# replace USED_ZIPFIAN_CONSTANT value in ZipfianGenerator.java file
sed -i "s/USED_ZIPFIAN_CONSTANT=.*;/USED_ZIPFIAN_CONSTANT=$new_value;/" /home/ubuntu/kaiju/contrib/YCSB/core/src/main/java/com/yahoo/ycsb/generator/ScrambledZipfianGenerator.java

echo "Constant values updated successfully!"
