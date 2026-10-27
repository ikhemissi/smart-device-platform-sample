import { Body1, Link, Title2 } from '@fluentui/react-components'
import { Link as RouterLink } from 'react-router'

export function NotFound() {
  return (
    <section>
      <Title2 as="h1">Page not found</Title2>
      <Body1 as="p">
        This page does not exist. <Link as="span"><RouterLink to="/devices">Go to the device list</RouterLink></Link>
      </Body1>
    </section>
  )
}
