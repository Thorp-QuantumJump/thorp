import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'

export function renderWithRouter(
  ui: ReactNode,
  options: { initialEntries?: string[] } = {},
) {
  return render(
    <MemoryRouter initialEntries={options.initialEntries ?? ['/']}>
      {ui}
    </MemoryRouter>,
  )
}
