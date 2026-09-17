import type { RegimeSearchResult } from '@/types/regime'
import { Disclosure } from './Disclosure'

interface Props { basis: RegimeSearchResult['basis']; context: string }

export function BasisNotice({ basis, context }: Props) {
  return (
    <Disclosure label="역사 비교" context={context}>
      <p>{basis.note}</p>
    </Disclosure>
  )
}
