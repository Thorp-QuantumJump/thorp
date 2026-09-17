import { Disclosure } from './Disclosure'

interface Props { note: string; context: string }

/** 모드 전환으로 기준월이 달라졌을 때 서버가 정한 한 문장만 보인다. */
export function BaselineChangeNotice({ note, context }: Props) {
  return <Disclosure label="기준월 변경" context={context}><p>{note}</p></Disclosure>
}
