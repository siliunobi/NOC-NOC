#!/bin/bash

# Function to recursively copy PDF files from a directory
copy_pdfs() {
  local source_dir="$1"
  local destination_dir="$2"

  # Iterate through each file in the source directory
  for file in "$source_dir"/*; do
    if [[ -f "$file" && "$file" =~ \.pdf$ ]]; then
      # Copy the PDF file to the destination directory
      cp "$file" "$destination_dir"
    elif [[ -d "$file" ]]; then
      # Recursively copy PDF files from subdirectories
      copy_pdfs "$file" "$destination_dir"
    fi
  done
}

# Read the input directory path from the user
read -p "Enter the path to the source directory: " source_path

# Remove the PlotsForPaper directory if it exists
if [[ -d "$source_path/PlotsForPaper" ]]; then
  rm -rf "$source_path/PlotsForPaper"
fi

# Create the PlotsForPaper directory inside the source directory
mkdir "$source_path/PlotsForPaper"

# Call the function to copy PDF files
copy_pdfs "$source_path" "$source_path/PlotsForPaper"

echo "PDF files copied to $source_path/PlotsForPaper directory."