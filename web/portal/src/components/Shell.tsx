import { Body1Strong, Button, Spinner, Subtitle1, makeStyles, mergeClasses, tokens } from '@fluentui/react-components'
import { SignOutRegular, SparkleRegular } from '@fluentui/react-icons'
import { useState } from 'react'
import { NavLink, Outlet, useMatch } from 'react-router'
import { useCurrentUser } from '../api/queries'
import { useSession } from '../auth/session'
import { useFeatures } from '../features'
import { AccessDenied } from '../pages/AccessDenied'
import { AssistantPanel } from './assistant/AssistantPanel'
import { ErrorState } from './ErrorState'

const useStyles = makeStyles({
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: tokens.spacingHorizontalXXL,
    padding: `${tokens.spacingVerticalM} ${tokens.spacingHorizontalXXL}`,
    backgroundColor: tokens.colorBrandBackground,
    color: tokens.colorNeutralForegroundOnBrand,
  },
  nav: { display: 'flex', gap: tokens.spacingHorizontalL, flexGrow: 1 },
  navLink: {
    color: tokens.colorNeutralForegroundOnBrand,
    textDecoration: 'none',
    padding: `${tokens.spacingVerticalXS} 0`,
    borderBottom: '2px solid transparent',
    ':focus-visible': { outline: `2px solid ${tokens.colorNeutralForegroundOnBrand}`, outlineOffset: '2px' },
  },
  active: { borderBottomColor: tokens.colorNeutralForegroundOnBrand, fontWeight: tokens.fontWeightSemibold },
  user: { display: 'flex', alignItems: 'center', gap: tokens.spacingHorizontalM },
  headerButton: { color: tokens.colorNeutralForegroundOnBrand },
  main: { padding: tokens.spacingHorizontalXXL },
  skip: {
    position: 'absolute',
    left: '-10000px',
    ':focus': { left: tokens.spacingHorizontalS, top: tokens.spacingVerticalS, zIndex: 1, background: tokens.colorNeutralBackground1, padding: tokens.spacingHorizontalS },
  },
})

/** Header (user, sign-out, navigation) and permission gate: pages only render for users who may read data. */
export function Shell() {
  const styles = useStyles()
  const session = useSession()
  const me = useCurrentUser()
  const features = useFeatures()
  const [assistantOpen, setAssistantOpen] = useState(false)
  const devicePage = useMatch('/devices/:deviceId')
  const assistantAvailable = features.assistant && me.data?.canRead === true

  let content
  if (me.isPending) {
    content = <Spinner label="Loading your profile…" />
  } else if (me.isError) {
    content = <ErrorState title="Your profile could not be loaded" error={me.error} onRetry={() => void me.refetch()} />
  } else if (!me.data.canRead) {
    content = <AccessDenied userPrincipalName={me.data.userPrincipalName} />
  } else {
    content = <Outlet />
  }

  return (
    <>
      <a href="#main" className={styles.skip}>Skip to main content</a>
      <header className={styles.header}>
        <Subtitle1 as="span">Smart Device Platform</Subtitle1>
        <nav aria-label="Sections" className={styles.nav}>
          {me.data?.canRead && (
            <>
              <SectionLink to="/devices">Devices</SectionLink>
              <SectionLink to="/sites">Sites</SectionLink>
              {features.anomalies && <SectionLink to="/anomalies">Anomalies</SectionLink>}
              {features.maintenance && <SectionLink to="/maintenance">Maintenance</SectionLink>}
            </>
          )}
        </nav>
        <div className={styles.user}>
          {assistantAvailable && (
            <Button
              appearance="transparent"
              className={styles.headerButton}
              icon={<SparkleRegular />}
              aria-expanded={assistantOpen}
              onClick={() => setAssistantOpen((open) => !open)}
            >
              Assistant
            </Button>
          )}
          {me.data && <Body1Strong>{me.data.displayName}</Body1Strong>}
          <Button appearance="transparent" className={styles.headerButton} icon={<SignOutRegular />} onClick={() => void session.signOut()}>
            Sign out
          </Button>
        </div>
      </header>
      <main id="main" className={styles.main} tabIndex={-1}>
        {content}
      </main>
      {assistantAvailable && (
        <AssistantPanel open={assistantOpen} onClose={() => setAssistantOpen(false)} deviceId={devicePage?.params.deviceId} />
      )}
    </>
  )
}

function SectionLink({ to, children }: { to: string; children: string }) {
  const styles = useStyles()
  return (
    <NavLink to={to} className={({ isActive }) => mergeClasses(styles.navLink, isActive && styles.active)}>
      {children}
    </NavLink>
  )
}
