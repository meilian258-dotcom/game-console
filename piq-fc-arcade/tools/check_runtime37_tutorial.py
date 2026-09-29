"""Validate the offline tutorial structure, not a browser-rendered visual review."""
import argparse, hashlib, json
from html.parser import HTMLParser
from pathlib import Path

class Tutorial(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack=[]; self.ids=set(); self.links=[]; self.headings=[]; self.lang=None
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        assert tag not in {'script','iframe','object','embed','form'}, tag
        assert not any(k.startswith('on') for k in a), a
        assert 'src' not in a, 'Tutorial must not fetch external resources'
        if 'id' in a:
            assert a['id'] not in self.ids; self.ids.add(a['id'])
        if tag=='html': self.lang=a.get('lang')
        if tag=='a':
            assert a.get('href','').startswith('#'), 'Offline anchors only'
            self.links.append(a['href'][1:])
        if tag=='h2': self.headings.append(a.get('id'))
        if tag not in {'meta','br','hr','img','input','link'}: self.stack.append(tag)
    def handle_endtag(self, tag):
        assert self.stack and self.stack.pop()==tag, 'Unbalanced '+tag

def main():
    p=argparse.ArgumentParser(); p.add_argument('--report',required=True,type=Path); a=p.parse_args()
    source=Path(__file__).resolve().parents[1]/'design/PIQ游戏机玩家教程-FC37.html'
    raw=source.read_bytes(); text=raw.decode('utf-8'); parser=Tutorial(); parser.feed(text); parser.close()
    assert not parser.stack and parser.lang=='zh-CN'
    sections=['install','files','home','controls','arcade','gun','gba','furniture','help']
    assert parser.headings==sections and set(parser.links)<=parser.ids
    assert '@media print' in text and '@media(max-width:650px)' in text and 'overflow-wrap:anywhere' in text
    assert 'url(' not in text and 'FC37 / SFC21 / Native13 / GBA5' in text
    assert 'ROM、BIOS' in text and '一键补齐' in text and 'NTFS' in text and '不保证' in text
    assert not a.report.exists()
    result=dict(schema='piq-runtime37-tutorial-1',ok=True,path=str(source),sha256=hashlib.sha256(raw).hexdigest().upper(),
        sections=sections,offline=True,balanced_html=True,rendered_preview=False,
        limits=['Browser file URL was denied by the tool policy; no bypass or rendered visual claim. Text facts were independently reviewed against current source.'])
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
