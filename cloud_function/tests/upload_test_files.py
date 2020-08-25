""" Helper script that copies all of the files for an arrays sample into the dev aou input bucket. This will trigger
the submit_aou_workload cloud function for each file. When all files have been uploaded, it will launch an arrays
workflow via the workflow launcher (but only if a workflow with that chipwell barcode & analysis version has not
been run before).

Usage: python upload_test_files.py -b <bucket>
"""

import argparse
import json
import random
import sys
import subprocess
import tempfile

arrays_path = "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/"
arrays_metadata_path = "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/"

def get_destination_paths(bucket, prefix):
    return {
        "arrays": f"gs://{bucket}/{prefix}/arrays/",
        "arrays_metadata": f"gs://{bucket}/{prefix}/arrays/metadata/",
        "ptc": f"gs://{bucket}/{prefix}/ptc.json"
    }

def get_ptc_json(bucket, prefix, chip_well_barcode, analysis_version, prod):
    return {
        "cromwell":
            "https://cromwell-aou.gotc-prod.broadinstitute.org" if prod
            else "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
        "environment": "aou-prod" if prod else "aou-dev",
        "uuid": None,
        "notifications": [{
            "analysis_version_number": analysis_version,
            "chip_well_barcode": chip_well_barcode,
            "green_idat_cloud_path": f"gs://{bucket}/{prefix}/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
            "params_file": f"gs://{bucket}/{prefix}/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
            "red_idat_cloud_path": f"gs://{bucket}/{prefix}/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat",
            "reported_gender": "Female",
            "sample_alias": "NA12878",
            "sample_lsid": "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878",
            "bead_pool_manifest_file": f"gs://{bucket}/{prefix}/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm",
            "cluster_file": f"gs://{bucket}/{prefix}/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt",
            "zcall_thresholds_file": f"gs://{bucket}/{prefix}/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt",
            "gender_cluster_file": f"gs://{bucket}/{prefix}/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt",
            "extended_chip_manifest_file": f"gs://{bucket}/{prefix}/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"
        }]
    }

def main(bucket, prod):
    chip_well_barcode = "7991775143_R01C01"
    analysis_version = random.randrange(sys.maxsize)
    prefix = f"chip_name/{chip_well_barcode}/{analysis_version}"
    ptc_json = get_ptc_json(bucket, prefix, chip_well_barcode, analysis_version, prod)
    destination_paths = get_destination_paths(bucket, prefix)
    with tempfile.TemporaryDirectory() as tmpdirname:
        with open(f'{tmpdirname}/ptc.json', 'w') as f:
            json.dump(ptc_json, f)
        subprocess.run(["gsutil", "cp", "-r", arrays_path, destination_paths["arrays"]])
        subprocess.run(["gsutil", "cp", "-r", arrays_metadata_path, destination_paths["arrays_metadata"]])
        subprocess.run(["gsutil", "cp", f"{tmpdirname}/ptc.json", destination_paths["ptc"]])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-b",
        "--bucket",
        dest="bucket",
        default="dev-aou-arrays-input",
        help="The upload destination bucket."
    )
    parser.add_argument(
        "-p",
        "--prod",
        action="store_true",
        help="Use infrastructure in broad-aou rather than broad-gotc-dev."
    )
    args = parser.parse_args()
    main(args.bucket, args.prod)
