#!/usr/bin/env python3
"""Optional stdio contract check with an installed Codex and isolated synthetic history.
No real session, account credentials, model calls, Herdr host, or network listener.
"""
import os, subprocess, json, tempfile, select, time, pathlib
thread='be368f85-ff58-4dc6-ad8f-071a690ae787'
started='2026-11-25T22:00:00.000Z'
with tempfile.TemporaryDirectory(dir='/tmp') as d:
 home=pathlib.Path(d)
 root=home/'sessions'/'2026'/'11'/'25'
 root.mkdir(parents=True)
 rollout=root/f'rollout-2026-11-25T22-00-00-{thread}.jsonl'
 def line(kind,payload):
  return json.dumps(dict(timestamp=started,type=kind,payload=payload), separators=(',',':'))+'\n'
 def event(kind,**payload):
  return line('event_msg',dict(type=kind,**payload))
 def items(turn, q, answer, end=True):
  text=lambda typ,t:[dict(type=typ,text=t)]
  return (event('task_started',turn_id=turn,model_context_window=10000,collaboration_mode_kind='default')+
      line('response_item',dict(type='message',role='user',content=text('input_text',q)))+
      event('user_message',message=q,images=[],local_images=[],text_elements=[])+
      line('response_item',dict(type='message',role='assistant',content=text('output_text',answer),phase='final_answer'))+
      event('agent_message',message=answer,phase='final_answer')+
      (event('task_complete',turn_id=turn,last_agent_message=answer) if end else ''))
 head=line('session_meta',dict(id=thread,timestamp=started,cwd=d,originator='codex_cli_rs',cli_version='0.172.0-alpha.2',source='cli',model_provider='openai'))
 baseline=items('old','An older question','An older answer')
 rollout.write_text(head+baseline)
 p=subprocess.Popen(['codex','app-server','--listen','stdio://'],cwd=d,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env={**os.environ,'CODEX_HOME':d},bufsize=0)
 def request(*msg):
  p.stdin.write(''.join(json.dumps(q)+'\n' for q in msg).encode());p.stdin.flush()
 def response(id):
  until=time.monotonic()+8
  while time.monotonic()<until:
   if select.select([p.stdout],[],[],.2)[0]:
    s=p.stdout.readline()
    if not s:break
    v=json.loads(s)
    if v.get('id')==id:return v
  raise RuntimeError('Timed out')
 def read(id):
  request(dict(id=id,method='thread/turns/list',params={'threadId':thread,'limit':4,'sortDirection':'desc','itemsView':'full'}))
  return response(id)
 try:
  request(dict(id=1,method='initialize',params={'clientInfo':{'name':'herdr-pocket','version':'0.93'}}),dict(method='initialized'))
  assert 'result' in response(1)
  first=read(2)
  with rollout.open('a') as f:f.write(items('new','What is 2 + 2?','Four. Want another example?',False))
  second=read(3)
  with rollout.open('a') as f:f.write(event('task_complete',turn_id='new',last_agent_message='Four. Want another example?'))
  third=read(4)
  assert first['result']['data'][0]['id']=='old'
  assert second['result']['data'][0]['status']=='interrupted'
  fresh=third['result']['data'][0]
  assert fresh['id']=='new' and fresh['status']=='completed'
  user, reply=fresh['items']
  assert user['content'][0]['text']=='What is 2 + 2?'
  assert reply['phase']=='final_answer' and reply['text']=='Four. Want another example?'
  print('Codex stdio returned exactly the new final answer from synthetic history.')
 finally:
  p.terminate();p.wait(4)
