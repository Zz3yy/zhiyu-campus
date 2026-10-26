import type { PlantKind } from './api'

export const plants: { kind: PlantKind; name: string; emotion: string; color: string; description: string; question: string }[] = [
  { kind: 'daisy', name: '小雏菊', emotion: '平静', color: '#759d87', description: '给安稳的此刻，留一片花瓣。', question: '此刻，有什么让你感到安稳？' },
  { kind: 'sunflower', name: '向日葵', emotion: '愉悦', color: '#bb8a35', description: '把一点阳光，留在这里。', question: '今天哪一个瞬间，让你想再记住一会？' },
  { kind: 'mimosa', name: '含羞草', emotion: '焦虑', color: '#9d7595', description: '小心翼翼的心情，也有位置。', question: '此刻，脑中最担心出现的情况是什么？' },
  { kind: 'moss', name: '苔藓', emotion: '低落', color: '#738759', description: '贴近大地，也是一种生长。', question: '这件事发生后，你怎样看待自己？' },
  { kind: 'cactus', name: '仙人掌', emotion: '愤怒', color: '#578478', description: '带着小刺，也值得被温柔对待。', question: '这件事里，你最在意的需要或界限是什么？' },
  { kind: 'dandelion', name: '蒲公英', emotion: '迷茫', color: '#8a977a', description: '还没找到方向，也可以先停一停。', question: '此刻有哪些已知的事，哪些还没有答案？' },
]
export const plantInfo = (kind: PlantKind) => plants.find(p => p.kind === kind)!

/** Original vector artwork. Each plant has its own silhouette and growth stages. */
export function Plant({ kind, stage = 2, className = '' }: { kind: PlantKind; stage?: number; className?: string }) {
  const mature = stage >= 2
  return <svg className={`plant-art ${className}`} viewBox="0 0 140 160" aria-hidden="true">
    <ellipse cx="70" cy="145" rx="35" ry="8" fill="#264c3212" />
    {kind === 'moss' ? <g><path d={mature ? 'M22 139Q15 118 33 115Q25 91 48 94Q63 67 81 94Q104 79 113 109Q135 118 119 139Z' : 'M31 139Q24 120 46 119Q62 99 78 118Q108 108 111 139Z'} fill="#869a62" /><path d="M40 130l5-10m11 13l3-17m13 17l6-18m13 16l-1-12m13 14l6-9" stroke="#4c704b" strokeWidth="3" strokeLinecap="round" />{mature && <g stroke="#688157" strokeWidth="3"><path d="M55 105V78m35 31V87"/><ellipse cx="55" cy="77" rx="5" ry="8" fill="#b79d63"/><ellipse cx="90" cy="86" rx="5" ry="8" fill="#b79d63"/></g>}</g>
    : kind === 'cactus' ? <g><path d={mature ? 'M55 141V104H39Q24 104 24 88V68Q24 54 36 54Q47 54 47 68V82H55V48Q55 29 70 29Q86 29 86 48V93H96V76Q96 64 107 64Q119 64 119 77V100Q119 115 101 115H86V141Z' : 'M53 141V88Q53 66 70 66Q87 66 87 88V141Z'} fill="#76a38c" stroke="#507c65" strokeWidth="2"/><path d="M66 139V78m10 61V75" stroke="#bdd0a5" strokeWidth="3" strokeLinecap="round"/>{mature && <g fill="#dd9ca6"><ellipse cx="70" cy="28" rx="7" ry="13"/><ellipse cx="70" cy="28" rx="7" ry="13" transform="rotate(60 70 28)"/><ellipse cx="70" cy="28" rx="7" ry="13" transform="rotate(120 70 28)"/><circle cx="70" cy="28" r="5" fill="#efdca3"/></g>}</g>
    : <g>
      <path d={stage === 0 ? 'M70 141Q75 117 69 103' : 'M70 141Q81 94 67 57'} fill="none" stroke="#63865e" strokeWidth="4" strokeLinecap="round"/>
      <path d="M72 128Q36 126 39 106Q67 105 72 128Z" fill="#8ba975"/><path d="M74 112Q101 113 109 91Q83 87 74 112Z" fill="#6f9866"/>
      {stage > 0 && <path d="M73 91Q49 95 43 76Q63 70 73 91Z" fill="#9bb683"/>}
      {kind === 'mimosa' && stage > 0 ? <g><path d="M70 94L32 73M74 83l33-24" stroke="#648762" strokeWidth="2"/>{[0,1,2,3].map(i => <g key={i} fill="#8aa77c"><ellipse cx={34+i*8} cy={69+i*4} rx="5" ry="10" transform={`rotate(-30 ${34+i*8} ${69+i*4})`}/><ellipse cx={86+i*7} cy={70-i*5} rx="4" ry="9" transform={`rotate(45 ${86+i*7} ${70-i*5})`}/></g>)}<circle cx="67" cy="51" r={mature ? 17 : 9} fill="#ceabc3"/>{mature && Array.from({length:12}, (_,i) => <path key={i} d="M67 51V29" stroke="#b98cad" strokeWidth="1.5" transform={`rotate(${i*30} 67 51)`}/>)}</g>
      : stage > 0 && kind === 'dandelion' ? <g>{Array.from({length:mature?14:7},(_,i) => <g key={i} transform={`rotate(${i*(mature?26:52)} 67 51)`}><path d="M67 51V25m0 0l-5-4m5 4l5-4" stroke="#acb9a2" strokeWidth="1.4"/><circle cx="67" cy="24" r="2.5" fill="#fcfaf0"/></g>)}<circle cx="67" cy="51" r="5" fill="#a2a378"/></g>
      : stage > 0 && kind !== 'mimosa' ? <g>{Array.from({length:kind==='sunflower'?12:9},(_,i) => <ellipse key={i} cx="67" cy={mature?30:41} rx={mature?8:5} ry={mature?17:9} transform={`rotate(${i*(kind==='sunflower'?30:40)} 67 51)`} fill={kind==='sunflower'?'#e5bd61':'#fffdf3'} stroke={kind==='sunflower'?'#cfa74c':'#e3e6d4'}/>) }<circle cx="67" cy="51" r={kind==='sunflower'?15:9} fill={kind==='sunflower'?'#85704e':'#ddbd69'}/>{kind==='sunflower' && <circle cx="67" cy="51" r="9" fill="none" stroke="#a18b61" strokeDasharray="2 4" strokeWidth="4"/>}</g> : <ellipse cx="69" cy="99" rx="7" ry="10" fill="#a9bd86"/>}
    </g>}
  </svg>
}

export function Keeper({ pose = 'welcome', className = '' }: { pose?: 'welcome' | 'listen' | 'care' | 'celebrate'; className?: string }) {
  return <svg viewBox="0 0 180 210" className={`keeper ${className} keeper-${pose}`} role="img" aria-label={`守园人知知，${{welcome:'向你招手',listen:'正在倾听',care:'陪你照顾花园',celebrate:'为你鼓励'}[pose]}`}>
    <ellipse cx="91" cy="195" rx="48" ry="9" fill="#244d3215"/>
    <path d="M67 167l-4 22q-13 11 0 12h20v-32m17 0v32h20q11-4-2-11l-6-24" fill="#65795a" stroke="#4f654c" strokeWidth="3"/>
    <path d="M59 112Q36 129 39 149l15 5 14-23m45-16q18 7 26-9l6-20" fill="none" stroke="#d9af89" strokeWidth="15" strokeLinecap="round"/>
    <path d="M61 113Q90 101 115 114l9 56q-35 14-68 0Z" fill="#e8d8ad" stroke="#bdc096" strokeWidth="2"/>
    <path d="M69 112v22h39v-22l9 6v53q-28 12-55-1v-51Z" fill="#7f9f7c"/><rect x="75" y="143" width="28" height="20" rx="7" fill="#a3b394"/><path d="M88 154v-9m0 4q-8-8-9-2q2 6 9 4m0-2q8-8 9-2q-2 6-9 4" fill="#e4deb6"/>
    <ellipse cx="87" cy="80" rx="38" ry="36" fill="#eac6a5"/>
    <path d="M53 79Q42 42 86 42q42 0 40 38l-12-16q-15 10-39-1L55 86Z" fill="#676148"/>
    <path d="M45 55Q46 8 87 17q37-2 40 38Z" fill="#a2b58b" stroke="#829b70" strokeWidth="2"/><path d="M45 48q43 13 81-2l1 11q-43 14-83-2Z" fill="#d7cd9a"/><ellipse cx="86" cy="58" rx="59" ry="12" fill="#b1c29a" stroke="#8da779" strokeWidth="2"/>
    <path d="M108 39q-1-22 17-23q4 17-17 23" fill="#628b60"/><path d="M109 38l9-14" stroke="#aec49b" strokeWidth="2"/>
    <g fill="#544e3e"><circle cx="74" cy="82" r="3"/><circle cx="101" cy="82" r="3"/></g><ellipse cx="63" cy="92" rx="7" ry="4" fill="#dca49a" opacity=".65"/><ellipse cx="111" cy="92" rx="7" ry="4" fill="#dca49a" opacity=".65"/>
    <path d={pose==='celebrate'?'M80 94q7 15 15 0Z':'M81 96q7 6 13-1'} fill={pose==='celebrate'?'#a87668':'none'} stroke="#a87668" strokeWidth="2" strokeLinecap="round"/>
    {pose==='care' && <g fill="#abbcab" stroke="#6e907c" strokeWidth="2"><path d="M125 140h28v25h-28Z"/><path d="M153 147l16-9-9 18h-7m-28-9q-15-12-15 4q0 8 15 4" fill="none"/></g>}
  </svg>
}

export function Landscape() {
  return <svg className="landscape" viewBox="0 0 1000 520" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <rect width="1000" height="520" fill="#e4f0e5"/><circle cx="760" cy="75" r="36" fill="#f1e7b9"/>
    <path d="M0 220Q150 100 340 208T700 196T1000 180V520H0Z" fill="#cbdcc7"/><path d="M0 300Q280 190 500 281T1000 235V520H0Z" fill="#b9d2b2"/>
    <path d="M620 520Q350 405 620 274L675 264Q451 408 790 520Z" fill="#e2d5ad"/>
    <g stroke="#9cac89" strokeWidth="7" fill="none"><path d="M25 281l950-28M25 300l950-28"/>{Array.from({length:20},(_,i)=><path key={i} d={`M${25+i*50} 270v40`}/>)}</g>
    <g transform="translate(735 96)"><path d="M48 180Q51 90 43 24" stroke="#9b9470" strokeWidth="25" fill="none"/><path d="M15 75Q-39 47 0 7Q-10-48 42-45Q79-80 112-36Q157-27 139 14Q182 64 123 83Z" fill="#8cac86"/><path d="M34 94h90v83H34Z" fill="#d9c69c"/><path d="M18 97l61-49 62 49Z" fill="#7a9273"/><rect x="68" y="126" width="26" height="51" rx="13" fill="#8f9b78"/><rect x="44" y="112" width="17" height="20" rx="3" fill="#f5e9c3"/><path d="M79 177v40m22-40v40m-22-27h22m-22 15h22" stroke="#a0936f" strokeWidth="4"/></g>
    <g fill="#96b58c"><ellipse cx="70" cy="390" rx="110" ry="45"/><ellipse cx="973" cy="435" rx="110" ry="58"/></g>
    <g fill="#f0e5bd">{[30,56,98,934,962,983].map((x,i)=><circle key={x} cx={x} cy={390+(i%3)*13} r="4"/>)}</g>
  </svg>
}
