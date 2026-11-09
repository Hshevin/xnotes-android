from pathlib import Path
import json,statistics,sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).resolve().parents[3]/'tmp/native-storage-benchmark/results'
fixtures=set()
for file in sorted(root.glob('*.jsonl')):
    lines=[json.loads(line) for line in file.read_text().splitlines() if line.strip()]
    meta=next((line for line in lines if 'fixture_sha256' in line),None)
    if meta is None:
        print(file.name+': no completed fixture metadata');continue
    fixtures.add(meta['fixture_sha256'])
    print(f"{file.stem}: {meta['bytes']} bytes; {meta['image_payload_bytes']} image bytes; {meta['manifest_bytes']} manifest bytes; SHA {meta['fixture_sha256']}")
    for operation in sorted({line['operation'] for line in lines if 'operation' in line}):
        samples=[line for line in lines if line.get('operation')==operation and not line.get('warmup')]
        if not samples:continue
        fields=['total_ms','storage_lock_held_ms','storage_lock_wait_ms','storage_lock_probe_wait_ms','editor_save_lock_held_ms','encode_ms','copy_ms']
        medians={field:round(statistics.median(line[field] for line in samples),3) for field in fields if all(field in line for line in samples)}
        print(operation,'n='+str(len(samples)),json.dumps(medians,sort_keys=True))
if len(fixtures)>1:raise SystemExit('Fixture hashes differ; comparisons are invalid')
