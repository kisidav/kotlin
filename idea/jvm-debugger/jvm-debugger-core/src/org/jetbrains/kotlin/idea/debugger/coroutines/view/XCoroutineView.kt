/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.view

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.JavaExecutionStack
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.impl.DebuggerContextUtil
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.SingleAlarm
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.XDebuggerManagerImpl
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreePanel
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeRestorer
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeState
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import javaslang.control.Either
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutineDebuggerContentInfo
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutineDebuggerContentInfo.Companion.XCOROUTINE_POPUP_ACTION_GROUP
import org.jetbrains.kotlin.idea.debugger.coroutines.command.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutines.command.CreationCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutines.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.ApplicationThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.CoroutinesDebugProbesProxy
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.ManagerThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutines.util.CreateContentParams
import org.jetbrains.kotlin.idea.debugger.coroutines.util.CreateContentParamsProvider
import org.jetbrains.kotlin.idea.debugger.coroutines.util.XDebugSessionListenerProvider
import org.jetbrains.kotlin.idea.debugger.coroutines.util.logger
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent


class XCoroutineView(val project: Project, val session: XDebugSession) :
    Disposable, XDebugSessionListenerProvider, CreateContentParamsProvider {
    private var needToRestoreState: Boolean = false
    val log by logger
    val splitter = OnePixelSplitter("SomeKey", 0.25f)
    val panel = XDebuggerTreePanel(project, session.debugProcess.editorsProvider, this, null, XCOROUTINE_POPUP_ACTION_GROUP, null)
    val alarm = SingleAlarm(Runnable { resetRoot() }, VIEW_CLEAR_DELAY, this)
    val javaDebugProcess = session.debugProcess as JavaDebugProcess
    val debugProcess: DebugProcessImpl = javaDebugProcess.debuggerSession.process
    val renderer = SimpleColoredTextIconPresentationRenderer()
    val managerThreadExecutor = ManagerThreadExecutor(debugProcess)
    val applicationThreadExecutor = ApplicationThreadExecutor()
    var treeState: XDebuggerTreeState? = null
    private var restorer: XDebuggerTreeRestorer? = null
    private var selectedNodeListener =  XDebuggerTreeSelectedNodeListener(panel.tree)

    companion object {
        private val VIEW_CLEAR_DELAY = 100 //ms
    }

    init {
        splitter.firstComponent = panel.mainPanel
        selectedNodeListener.installOn()
    }


    fun saveState() {
        DebuggerUIUtil.invokeLater {
            if (! (panel.tree.root is EmptyNode)) {
                treeState = XDebuggerTreeState.saveState(panel.tree)
                log.info("Tree state saved")
            }
        }
    }

    fun resetRoot() {
        DebuggerUIUtil.invokeLater {
            panel.tree.setRoot(EmptyNode(), false)
        }
    }

    fun renewRoot(suspendContext: XSuspendContext) {
        panel.tree.setRoot(XCoroutinesRootNode(suspendContext), false)
        if(treeState != null) {
            restorer?.dispose()
            restorer = treeState?.restoreState(panel.tree)
            log.info("Tree state restored")
        }
    }

    override fun dispose() {
        restorer?.dispose()
    }

    fun forceClear() {
        alarm.cancel()
    }

    override fun debugSessionListener(session: XDebugSession) =
        CoroutineViewDebugSessionListener(session, this)

    override fun createContentParams(): CreateContentParams =
        CreateContentParams(
            CoroutineDebuggerContentInfo.XCOROUTINE_THREADS_CONTENT,
            splitter,
            KotlinBundle.message("debugger.session.tab.xcoroutine.title"),
            null,
            panel.tree
        )

    inner class EmptyNode : XValueContainerNode<XValueContainer>(panel.tree, null, true, object : XValueContainer() {})

    inner class XCoroutinesRootNode(suspendContext: XSuspendContext) :
        XValueContainerNode<CoroutineGroupContainer>(panel.tree, null, false, CoroutineGroupContainer(suspendContext, "Default group"))

    inner class CoroutineGroupContainer(val suspendContext: XSuspendContext, val groupName: String) : XValueContainer() {
        override fun computeChildren(node: XCompositeNode) {
            val groups = XValueChildrenList.singleton(CoroutineContainer(suspendContext, groupName))
            node.addChildren(groups, true)
        }
    }

    inner class CoroutineContainer(
        val suspendContext: XSuspendContext,
        val groupName: String
    ) : RendererContainer(renderer.renderGroup(groupName)) {

        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.on(suspendContext).schedule {
                val debugProbesProxy = CoroutinesDebugProbesProxy(suspendContext)

                var coroutineCache = debugProbesProxy.dumpCoroutines()
                if (coroutineCache.isOk()) {
                    val children = XValueChildrenList()
                    coroutineCache.cache.forEach {
                        children.add(FramesContainer(it, suspendContext))
                    }
                    node.addChildren(children, true)
                } else {
                    node.addChildren(XValueChildrenList.singleton(ErrorNode("Error occured while fetching information")), true)
                }
            }
        }
    }

    inner class ErrorNode(val error: String) : RendererContainer(renderer.renderErrorNode(error))

    inner class FramesContainer(
        private val infoData: CoroutineInfoData,
        private val suspendContext: XSuspendContext
    ) : RendererContainer(renderer.render(infoData)) {

        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.on(suspendContext).schedule {
                val debugProbesProxy = CoroutinesDebugProbesProxy(suspendContext)
                val children = XValueChildrenList()
                debugProbesProxy.frameBuilder().build(infoData)
                val creationStack = mutableListOf<CreationCoroutineStackFrameItem>()
                infoData.stackFrameList.forEach {
                    if (it is CreationCoroutineStackFrameItem)
                        creationStack.add(it)
                    else
                        children.add(CoroutineFrameValue(it))
                }
                children.add(CreationFramesContainer(infoData, creationStack))
                node.addChildren(children, true)
            }
        }
    }

    inner class CreationFramesContainer(
        private val infoData: CoroutineInfoData,
        private val creationFrames: List<CreationCoroutineStackFrameItem>
    ) : RendererContainer(renderer.renderCreationNode(infoData)) {

        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList()

            creationFrames.forEach {
                children.add(CoroutineFrameValue(it))
            }
            node.addChildren(children, true)
        }
    }

    inner class CoroutineFrameValue(val frame: CoroutineStackFrameItem
    ) : XNamedValue(frame.uniqueId()) {
        override fun computePresentation(node: XValueNode, place: XValuePlace) =
            applyRenderer(node, renderer.render(frame.location()))
    }

    private fun applyRenderer(node: XValueNode, presentation: SimpleColoredTextIcon) =
        node.setPresentation(presentation.icon, presentation.valuePresentation(), presentation.hasChildrens)

    open inner class RendererContainer(val presentation: SimpleColoredTextIcon) : XNamedValue(presentation.simpleString()) {
        override fun computePresentation(node: XValueNode, place: XValuePlace) =
            applyRenderer(node, presentation)
    }

    inner class XDebuggerTreeSelectedNodeListener(val tree: XDebuggerTree) {

        fun installOn() {
            object : DoubleClickListener() {
                override fun onDoubleClick(e: MouseEvent) =
                    nodeSelected(Either.left(e))
            }.installOn(tree)

            tree.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val key = e.keyCode
                    if (key == KeyEvent.VK_ENTER || key == KeyEvent.VK_SPACE || key == KeyEvent.VK_RIGHT)
                        nodeSelected(Either.right(e))
                }
            })
        }

        fun nodeSelected(event: Either<MouseEvent, KeyEvent>) : Boolean {
            val selectedNodes = tree.getSelectedNodes(XValueNodeImpl::class.java, null)
            if (selectedNodes.size == 1) {
                val node = selectedNodes[0]
                val valueContainer = node.valueContainer
                if (valueContainer is XCoroutineView.CoroutineFrameValue) {
                    val frame = valueContainer.frame
                    val threadProxy = valueContainer.frame.frame.threadProxy()
                    val threadSuspendContext = session.suspendContext as SuspendContextImpl
                    val isCurrentContext = threadSuspendContext.thread == threadProxy
                    managerThreadExecutor.on(threadSuspendContext).schedule {
                        val executionStack = CoroutineDebuggerExecutionStack(threadProxy, isCurrentContext)
                        executionStack.initTopFrame()
                        applicationThreadExecutor.schedule {
                            session.setCurrentStackFrame(executionStack, frame.stackFrame)
                        }
                    }
                }
            }
            return false
        }
    }

    inner class CoroutineDebuggerExecutionStack(threadReferenceProxy: ThreadReferenceProxyImpl, isCurrentContext: Boolean) :
        JavaExecutionStack(threadReferenceProxy, debugProcess, isCurrentContext)
}

