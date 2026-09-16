import React from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }
  static getDerivedStateFromError(error) {
    return { error }
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '32px', fontFamily: 'monospace', color: '#eb5757', background: '#08090a', minHeight: '100vh' }}>
          <div style={{ fontSize: '13px', color: '#8a8f98', marginBottom: '8px' }}>OCULUS // RUNTIME ERROR</div>
          <pre style={{ fontSize: '12px', color: '#eb5757', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {this.state.error.toString()}
            {'\n\n'}
            {this.state.error.stack}
          </pre>
        </div>
      )
    }
    return this.props.children
  }
}

createRoot(document.getElementById('root')).render(
  <ErrorBoundary>
    <App />
  </ErrorBoundary>,
)
