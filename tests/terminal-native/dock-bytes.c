#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "vterm.h"
static unsigned char output[256];
static size_t length;
static void collect(const char *bytes, size_t count, void *user) {
  (void)user;
  assert(length+count<=sizeof(output));
  memcpy(output+length,bytes,count);
  length+=count;
}
static void expect(const char *description, const char *want, size_t count) {
  assert(length==count && memcmp(output,want,count)==0);
  printf("%s:",description);
  for(size_t i=0;i<length;i++) printf(" %02x",output[i]);
  puts("");
  length=0;
}
static void dictated_line(VTerm *vt, int press_enter) {
  const unsigned chars[]={'c','a','f',0xe9,' ',0x1f600};
  for(size_t i=0;i<sizeof(chars)/sizeof(chars[0]);i++)
    vterm_keyboard_unichar(vt,chars[i],VTERM_MOD_NONE);
  if(press_enter) vterm_keyboard_key(vt,VTERM_KEY_ENTER,VTERM_MOD_NONE);
}
static void pasted_text(VTerm *vt) {
  // Match the pinned JNI Terminal::paste: native mode markers around unmodified UTF-8.
  const char text[]="caf\xc3\xa9 \xf0\x9f\x98\x80\nsecond";
  vterm_keyboard_start_paste(vt);
  collect(text,sizeof(text)-1,NULL);
  vterm_keyboard_end_paste(vt);
}
int main(int argc, char **argv) {
  VTerm *vt=vterm_new(24,80);
  vterm_set_utf8(vt,1);
  vterm_state_reset(vterm_obtain_state(vt),1);
  vterm_output_set_callback(vt,collect,NULL);
  if(argc>1) {
    dictated_line(vt,strcmp(argv[1],"--dictation-text") != 0);
    fwrite(output,1,length,stdout);
    vterm_free(vt);
    return 0;
  }
  vterm_keyboard_unichar(vt,'c',VTERM_MOD_CTRL|VTERM_MOD_ALT);
  vterm_keyboard_unichar(vt,'x',VTERM_MOD_NONE);
  expect("combined first IME text key, next plain", "\033\003x",3);
  vterm_keyboard_unichar(vt,'c',VTERM_MOD_ALT);
  expect("Ctrl disarmed, Alt retained","\033c",2);
  vterm_keyboard_unichar(vt,'c',VTERM_MOD_CTRL);
  expect("Alt disarmed, Ctrl retained","\003",1);
  vterm_keyboard_key(vt,VTERM_KEY_UP,VTERM_MOD_ALT|VTERM_MOD_CTRL);
  expect("combined virtual Up","\033[1;7A",6);
  vterm_keyboard_key(vt,VTERM_KEY_UP,VTERM_MOD_NONE);
  expect("next unmodified Up","\033[A",3);
  vterm_keyboard_key(vt,VTERM_KEY_ENTER,VTERM_MOD_NONE);
  expect("D-pad Enter in default mode","\r",1);
  dictated_line(vt,1);
  expect("dictation text then default native Enter","caf\xc3\xa9 \xf0\x9f\x98\x80\r",11);
  vterm_input_write(vt,"\033[20h",5);
  vterm_keyboard_key(vt,VTERM_KEY_ENTER,VTERM_MOD_NONE);
  expect("D-pad Enter in newline mode","\r\n",2);
  dictated_line(vt,1);
  expect("dictation text then native newline-mode Enter","caf\xc3\xa9 \xf0\x9f\x98\x80\r\n",12);
  dictated_line(vt,0);
  expect("dictation text without Enter even in newline mode","caf\xc3\xa9 \xf0\x9f\x98\x80",10);
  pasted_text(vt);
  expect("paste without bracketed mode or added Enter","caf\xc3\xa9 \xf0\x9f\x98\x80\nsecond",17);
  vterm_input_write(vt,"\033[?2004h",8);
  pasted_text(vt);
  expect("paste with bracketed mode and no added Enter","\033[200~caf\xc3\xa9 \xf0\x9f\x98\x80\nsecond\033[201~",29);
  vterm_free(vt);
}
