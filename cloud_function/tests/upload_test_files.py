""" Helper script that copies all of the files for an arrays sample into the dev aou input bucket. This will trigger
the submit_aou_workload cloud function for each file. When all files have been uploaded, it will launch an arrays
workflow via the workflow launcher (but only if a workflow with that chipwell barcode & analysis version has not
been run before). """

import json
import uuid
import subprocess

bucket = "dev-aou-arrays-input"
uuid = uuid.uuid4()

arrays_path = "gs://broad-gotc-test-storage/arrays/HumanExome-12v1-1_A/"
arrays_metadata_path = "gs://broad-gotc-test-storage/arrays/metadata/HumanExome-12v1-1_A/"
dest_arrays_path = f"gs://{bucket}/{uuid}/v1/arrays/"
dest_arrays_metadata_path = f"gs://{bucket}/{uuid}/v1/arrays/metadata/"
dest_ptc_json_path = f"gs://{bucket}/{uuid}/v1/ptc.json"

ptc_json = {
    "cromwell": "https://cromwell-gotc-auth.gotc-dev.broadinstitute.org/",
    "environment": "aou-dev",
    "uuid": None,
    "notifications": [{
        "analysis_version_number": 1,
        "chip_well_barcode": str(uuid),
        "green_idat_cloud_path": f"gs://{bucket}/{uuid}/v1/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Grn.idat",
        "params_file": f"gs://{bucket}/{uuid}/v1/arrays/HumanExome-12v1-1_A/inputs/7991775143_R01C01/params.txt",
        "red_idat_cloud_path": f"gs://{bucket}/{uuid}/v1/arrays/HumanExome-12v1-1_A/idats/7991775143_R01C01/7991775143_R01C01_Red.idat",
        "reported_gender": "Female",
        "sample_alias": "NA12878",
        "sample_lsid": "broadinstitute.org:bsp.dev.sample:NOTREAL.NA12878",
        "bead_pool_manifest_file": f"gs://{bucket}/{uuid}/v1/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.bpm",
        "cluster_file": f"gs://{bucket}/{uuid}/v1/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_CEPH_A.egt",
        "zcall_thresholds_file": f"gs://{bucket}/{uuid}/v1/arrays/metadata/HumanExome-12v1-1_A/IBDPRISM_EX.egt.thresholds.txt",
        "gender_cluster_file": f"gs://{bucket}/{uuid}/v1/arrays/metadata/HumanExome-12v1-1_A/HumanExomev1_1_gender.egt",
        "extended_chip_manifest_file": f"gs://{bucket}/{uuid}/v1/arrays/metadata/HumanExome-12v1-1_A/HumanExome-12v1-1_A.1.3.extended.csv"
    }]
}

def main():
    with open('ptc.json', 'w') as f:
        json.dump(ptc_json, f)
    subprocess.run(["gsutil", "cp", "-r", arrays_path, dest_arrays_path])
    subprocess.run(["gsutil", "cp", "-r", arrays_metadata_path, dest_arrays_metadata_path])
    subprocess.run(["gsutil", "cp", "ptc.json", dest_ptc_json_path])


if __name__ == '__main__':
    main()
