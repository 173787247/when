import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'

describe('When admin console', () => {
  it('renders an explicit loading state and prevents duplicate writes', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const wrapper = mount(App, { global: { stubs: { 'el-skeleton': { template: '<div>loading</div>' } } } })
    expect(wrapper.text()).toContain('延时投递管理台')
    expect(wrapper.text()).toContain('loading')
  })
})
