#include <cstdio>
#include <string>
#include <fstream>
#include <vector>
#include <cstring>
#include <unistd.h>
#include "third_party/sherpa-c-api.h"
int main(int argc,char**argv){
 setbuf(stdout,nullptr);if(argc<2)return 1;std::string base=argv[1];
 std::string enc=base+"/asr/encoder.onnx",dec=base+"/asr/decoder.onnx",join=base+"/asr/joiner.onnx",tok=base+"/asr/tokens.txt",bpe=base+"/asr/bpe.vocab";
 SherpaOnnxOnlineRecognizerConfig cfg{};cfg.feat_config.sample_rate=16000;cfg.feat_config.feature_dim=80;
 cfg.model_config.transducer.encoder=enc.c_str();cfg.model_config.transducer.decoder=dec.c_str();cfg.model_config.transducer.joiner=join.c_str();cfg.model_config.tokens=tok.c_str();cfg.model_config.num_threads=2;cfg.model_config.provider="cpu";cfg.model_config.model_type="zipformer";cfg.model_config.modeling_unit="cjkchar+bpe";cfg.model_config.bpe_vocab=bpe.c_str();cfg.decoding_method="modified_beam_search";cfg.max_active_paths=4;cfg.hotwords_score=1.5f;
 printf("Page size: %ld\nLoading Android ASR runtime %s\n",sysconf(_SC_PAGESIZE),SherpaOnnxGetVersionStr());
 auto r=SherpaOnnxCreateOnlineRecognizer(&cfg);if(!r)return 2;auto s=SherpaOnnxCreateOnlineStreamWithHotwords(r,"语音识别\n输入法");if(!s)return 3;puts("Model ready");
 std::ifstream f(base+"/sample.wav",std::ios::binary);std::vector<char>wav((std::istreambuf_iterator<char>(f)),std::istreambuf_iterator<char>());size_t offset=12,start=0,len=0;
 while(offset+8<wav.size()){uint32_t size;memcpy(&size,wav.data()+offset+4,4);if(!memcmp(wav.data()+offset,"data",4)){start=offset+8;len=size;break;}offset+=8+size+size%2;}
 if(!start||start+len>wav.size())return 4;int changes=0;std::string previous;
 for(size_t n=0;n<len/2;n+=1280){size_t count=std::min((size_t)1280,len/2-n);std::vector<float>pcm(count);for(size_t i=0;i<count;i++){int16_t v;memcpy(&v,wav.data()+start+(n+i)*2,2);pcm[i]=v/32768.f;}
  SherpaOnnxOnlineStreamAcceptWaveform(s,16000,pcm.data(),count);while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);
  auto result=SherpaOnnxGetOnlineStreamResult(r,s);std::string text=result&&result->text?result->text:"";if(!text.empty()&&text!=previous){changes++;previous=text;}SherpaOnnxDestroyOnlineRecognizerResult(result);
 }
 std::vector<float>silence(8000,0);SherpaOnnxOnlineStreamAcceptWaveform(s,16000,silence.data(),silence.size());SherpaOnnxOnlineStreamInputFinished(s);while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);
 auto result=SherpaOnnxGetOnlineStreamResult(r,s);bool good=result&&result->text&&strlen(result->text)>0&&changes>0;printf("Partial updates: %d\nResult: %s\n",changes,result?result->text:"");SherpaOnnxDestroyOnlineRecognizerResult(result);SherpaOnnxDestroyOnlineStream(s);SherpaOnnxDestroyOnlineRecognizer(r);puts(good?"PASS ASR Android native":"FAIL");return good?0:5;
}
