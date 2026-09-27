// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include "rime_api.h"
static RimeApi* api = nullptr;
static RimeSessionId sid = 0;
static size_t candidateLimit = 30;
static std::string sharedDir, userDir;
static std::string quote(const char* s) {
  std::string o="\""; if(s) for(const unsigned char* p=(const unsigned char*)s; *p; ++p) {
    if(*p=='"'||*p=='\\') {o+='\\';o+=(char)*p;}
    else if(*p<32) {char b[7];snprintf(b,7,"\\u%04x",*p);o+=b;}
    else o+=(char)*p;
  } return o+'"';
}
static jbyteArray bytes(JNIEnv* e,const std::string& s) { auto a=e->NewByteArray(s.size());e->SetByteArrayRegion(a,0,s.size(),(const jbyte*)s.data());return a; }
static jbyteArray state(JNIEnv* e) {
  if(!sid) return bytes(e,"{}");
  RIME_STRUCT(RimeCommit,c); std::string committed;
  if(api->get_commit(sid,&c)){if(c.text)committed=c.text;api->free_commit(&c);}
  RIME_STRUCT(RimeContext,x); std::string out="{\"commit\":"+quote(committed.c_str());
  out+=",\"raw\":"+quote(api->get_input(sid));out+=",\"caret\":"+std::to_string(api->get_caret_pos(sid));
  if(api->get_context(sid,&x)) {
    out+=",\"preedit\":"+quote(x.composition.preedit)+",\"selStart\":"+std::to_string(x.composition.sel_start)+",\"selEnd\":"+std::to_string(x.composition.sel_end);
    api->free_context(&x);
  }
  out+=",\"candidates\":[";RimeCandidateListIterator it{};bool first=true;
  bool more=false;std::string reading;
  if(api->candidate_list_begin(sid,&it)){
    size_t n=0;
    while(api->candidate_list_next(&it)){
      if(n++>=candidateLimit){more=true;break;}
      if(first && it.candidate.comment)reading=it.candidate.comment;
      if(!first)out+=',';first=false;out+=quote(it.candidate.text);
    }
    api->candidate_list_end(&it);
  }
  return bytes(e,out+"],\"reading\":"+quote(reading.c_str())+",\"hasMore\":"+(more ? "true" : "false")+"}");
}
extern "C" JNIEXPORT jboolean JNICALL Java_app_loop_ime_RimeNative_init(JNIEnv* e,jobject,jstring shared,jstring user) {
  if(sid)return true;
  // dlopen deliberately uses only the stable C API. The donor application's JNI_OnLoad is not invoked.
  auto h=dlopen("librime_jni.so",RTLD_NOW|RTLD_LOCAL);if(!h)return false;
  auto get=(RimeApi*(*)())dlsym(h,"rime_get_api");if(!get)return false;api=get();
  auto a=e->GetStringUTFChars(shared,nullptr);sharedDir=a;e->ReleaseStringUTFChars(shared,a);
  auto b=e->GetStringUTFChars(user,nullptr);userDir=b;e->ReleaseStringUTFChars(user,b);
  RIME_STRUCT(RimeTraits,t);t.shared_data_dir=sharedDir.c_str();t.user_data_dir=userDir.c_str();
  t.distribution_name="Loop";t.distribution_code_name="loop";t.distribution_version="0.1.2";t.app_name="rime.loop";
  t.min_log_level=3;t.log_dir="";api->setup(&t);api->initialize(&t);
  if(api->start_maintenance(false))api->join_maintenance_thread();
  sid=api->create_session();if(!sid)return false;
  if(!api->select_schema(sid,"loop_t9")){api->destroy_session(sid);sid=0;return false;}
  api->set_option(sid,"ascii_mode",false);api->set_option(sid,"zh_simp",true);
  api->set_option(sid,"_no_learning",true);return true;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_loop_ime_RimeNative_event(JNIEnv* e,jobject,jint key,jint kind) {
  if(sid){
    if(kind==5){candidateLimit+=60;return state(e);}
    candidateLimit=30;
    if(kind==1)api->select_candidate(sid,key);
    else if(kind==2)api->clear_composition(sid);
    else if(kind==3)api->commit_composition(sid);
    else if(kind==4){
      api->clear_composition(sid);
      api->select_schema(sid,key==1 ? "loop_t9" : "luna_pinyin_simp");
      api->set_option(sid,"ascii_mode",false);api->set_option(sid,"zh_simp",true);
      api->set_option(sid,"_no_learning",true);
    }
    else api->process_key(sid,key,0);
  }return state(e);
}
