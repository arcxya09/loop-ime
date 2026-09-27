// Real Android librime smoke test. Run with a disposable data directory.
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include "../app/src/main/cpp/rime_api.h"
int main(int argc,char**argv){
 setbuf(stdout,nullptr);if(argc<2)return 1;
 std::string dir=argv[1],lib=dir+"/librime_jni.so",shared=dir+"/rime",user=dir+"/user";
 printf("Page size: %ld\n",sysconf(_SC_PAGESIZE));
 auto h=dlopen(lib.c_str(),RTLD_NOW|RTLD_LOCAL);if(!h){printf("dlopen: %s\n",dlerror());return 2;}
 auto get=(RimeApi*(*)())dlsym(h,"rime_get_api");if(!get)return 3;auto api=get();
 RIME_STRUCT(RimeTraits,t);t.shared_data_dir=shared.c_str();t.user_data_dir=user.c_str();t.app_name="rime.loop.test";t.distribution_name="Loop";t.distribution_code_name="loop";t.distribution_version="0.1.2";t.min_log_level=3;t.log_dir="";
 api->setup(&t);api->initialize(&t);puts("Deploying Rime");if(api->start_maintenance(true))api->join_maintenance_thread();
 auto sid=api->create_session();if(!sid)return 4;
 struct Case {const char* schema;const char* code;const char* expected;const char* traditional;};
 Case cases[]={
   {"loop_t9","64426","你好",""},
   {"loop_t9","94664486","中国","中國"},
   {"loop_t9","42698","汉语","漢語"},
   {"loop_t9","98394","学习","學習"},
   {"loop_t9","94'26","西安","西安"},
   {"luna_pinyin_simp","hanyu","汉语","漢語"},
   {"luna_pinyin_simp","xuexi","学习","學習"},
   {"loop_t9","64426","你好",""}
 };
 for(auto test:cases){
   if(!api->select_schema(sid,test.schema))return 5;
   api->set_option(sid,"ascii_mode",false);api->set_option(sid,"zh_simp",true);api->set_option(sid,"_no_learning",true);
   api->clear_composition(sid);
   std::string typed;
   for(char ch:std::string(test.code)){
     typed+=ch;api->process_key(sid,ch,0);
     if(typed!=api->get_input(sid)){printf("FAIL premature commit: %s / %s\n",typed.c_str(),api->get_input(sid));return 6;}
   }
   // Delete and restore the last spelling key without committing a digit.
   api->process_key(sid,0xff08,0);
   if(typed.substr(0,typed.size()-1)!=api->get_input(sid))return 7;
   api->process_key(sid,typed.back(),0);
   int found=-1,index=0;RimeCandidateListIterator it{};
   if(api->candidate_list_begin(sid,&it)){
     while(index<30 && api->candidate_list_next(&it)){
       if(!strcmp(it.candidate.text,test.expected))found=index;
       if(strcmp(test.expected,test.traditional) && strlen(test.traditional) && !strcmp(it.candidate.text,test.traditional)){
         printf("FAIL traditional candidate: %s\n",it.candidate.text);return 8;
       }
       if(index<5)printf("%s %s candidate %d: %s\n",test.schema,test.code,index,it.candidate.text);
       index++;
     }api->candidate_list_end(&it);
   }
   if(found<0){printf("FAIL missing %s\n",test.expected);return 9;}
   api->select_candidate(sid,found);
   RIME_STRUCT(RimeCommit,c);if(!api->get_commit(sid,&c))return 10;
   bool good=c.text&&!strcmp(c.text,test.expected);printf("Commit: %s\n",c.text);api->free_commit(&c);if(!good)return 11;
 }
 api->destroy_session(sid);api->finalize();puts("PASS 8 real Rime T9 / simplified / layout switch / backspace cases");return 0;
}
