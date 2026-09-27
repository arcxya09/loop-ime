"""Decode the upstream public bilingual fixture with the exact bundled model (desktop CPU)."""
from pathlib import Path
import json, time, wave
import numpy as np
import sherpa_onnx
p=Path(__file__).resolve().parents[1]
m=p/'app/src/main/assets/asr'
hot=p/'tests/hotwords.txt'
r=sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=str(m/'tokens.txt'),encoder=str(m/'encoder.onnx'),decoder=str(m/'decoder.onnx'),joiner=str(m/'joiner.onnx'),
    num_threads=2,sample_rate=16000,feature_dim=80,decoding_method='modified_beam_search',max_active_paths=4,
    modeling_unit='cjkchar+bpe',bpe_vocab=str(m/'bpe.vocab'),hotwords_file=str(hot),enable_endpoint_detection=False)
with wave.open(str(p/'app/src/androidTest/assets/sample.wav')) as wav:
    assert wav.getnchannels()==1 and wav.getsampwidth()==2 and wav.getframerate()==16000
    audio=np.frombuffer(wav.readframes(wav.getnframes()),dtype=np.int16).astype(np.float32)/32768
stream=r.create_stream()
started=time.perf_counter();partial_count=0;previous=''
for pos in range(0,len(audio),1280):
    stream.accept_waveform(16000,audio[pos:pos+1280])
    while r.is_ready(stream):r.decode_stream(stream)
    text=r.get_result(stream)
    if text and text!=previous:partial_count+=1;previous=text
stream.accept_waveform(16000,np.zeros(8000,dtype=np.float32));stream.input_finished()
while r.is_ready(stream):r.decode_stream(stream)
text=r.get_result(stream);elapsed=time.perf_counter()-started
assert partial_count>0 and text.strip()
print(json.dumps({'passed':True,'runtime':'desktop CPU (not a phone benchmark)','sample':'public upstream test_wavs/1.wav','result':text,'partial_updates':partial_count,'audio_seconds':len(audio)/16000,'decode_seconds':elapsed,'rtf':elapsed/(len(audio)/16000)},ensure_ascii=False,indent=2))
