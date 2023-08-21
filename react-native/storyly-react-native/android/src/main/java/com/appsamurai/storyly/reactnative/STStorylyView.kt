package com.appsamurai.storyly.reactnative

import android.content.Context
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.appsamurai.storyly.*
import com.appsamurai.storyly.analytics.StorylyEvent
import com.appsamurai.storyly.data.managers.product.STRCart
import com.appsamurai.storyly.data.managers.product.STRCartEventResult
import com.appsamurai.storyly.data.managers.product.STRCartItem
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.properties.Delegates

class STStorylyView(context: Context) : FrameLayout(context) {

    private var cartUpdateSuccessFailIdMap: MutableMap<String, String> = mutableMapOf()
    private var cartUpdateSuccessEvents: MutableMap<String, ((STRCart?) -> Unit)?> = mutableMapOf()
    private var cartUpdateFailEvents: MutableMap<String, ((STRCartEventResult) -> Unit)?> = mutableMapOf()

    internal var storylyView: StorylyView? by Delegates.observable(null) { _, _, _ ->
        removeAllViews()
        val storylyView = storylyView ?: return@observable
        addView(storylyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        storylyView.storylyListener = object : StorylyListener {
            override fun storylyActionClicked(storylyView: StorylyView, story: Story) {
                sendEvent(STStorylyManager.EVENT_STORYLY_ACTION_CLICKED, createStoryMap(story))
            }

            override fun storylyLoaded(
                storylyView: StorylyView,
                storyGroupList: List<StoryGroup>,
                dataSource: StorylyDataSource
            ) {
                sendEvent(STStorylyManager.EVENT_STORYLY_LOADED, Arguments.createMap().also { storyGroupListMap ->
                    storyGroupListMap.putArray("storyGroupList", Arguments.createArray().also { storyGroups ->
                        storyGroupList.forEach { storyGroup ->
                            storyGroups.pushMap(createStoryGroupMap(storyGroup))
                        }
                    })
                    storyGroupListMap.putString("dataSource", dataSource.value)
                })
            }

            override fun storylyLoadFailed(
                storylyView: StorylyView,
                errorMessage: String
            ) {
                sendEvent(STStorylyManager.EVENT_STORYLY_LOAD_FAILED, Arguments.createMap().also { eventMap ->
                    eventMap.putString("errorMessage", errorMessage)
                })
            }

            override fun storylyEvent(
                storylyView: StorylyView,
                event: StorylyEvent,
                storyGroup: StoryGroup?,
                story: Story?, storyComponent: StoryComponent?
            ) {
                sendEvent(STStorylyManager.EVENT_STORYLY_EVENT, Arguments.createMap().also { eventMap ->
                    eventMap.putString("event", event.name)
                    storyGroup?.let { eventMap.putMap("storyGroup", createStoryGroupMap(it)) }
                    story?.let { eventMap.putMap("story", createStoryMap(it)) }
                    storyComponent?.let { eventMap.putMap("storyComponent", createStoryComponentMap(it)) }
                })
            }

            override fun storylyStoryShown(storylyView: StorylyView) {
                sendEvent(STStorylyManager.EVENT_STORYLY_STORY_PRESENTED, null)
            }

            override fun storylyStoryShowFailed(storylyView: StorylyView, errorMessage: String) {
                sendEvent(STStorylyManager.EVENT_STORYLY_STORY_PRESENT_FAILED, Arguments.createMap().also { eventMap ->
                    eventMap.putString("errorMessage", errorMessage)
                })
                sendEvent(STStorylyManager.EVENT_STORYLY_STORY_PRESENTED, null)
            }

            override fun storylyStoryDismissed(storylyView: StorylyView) {
                sendEvent(STStorylyManager.EVENT_STORYLY_STORY_DISMISSED, null)
            }

            override fun storylyUserInteracted(
                storylyView: StorylyView,
                storyGroup: StoryGroup,
                story: Story,
                storyComponent: StoryComponent
            ) {
                sendEvent(STStorylyManager.EVENT_STORYLY_USER_INTERACTED, Arguments.createMap().also { eventMap ->
                    eventMap.putMap("storyGroup", createStoryGroupMap(storyGroup))
                    eventMap.putMap("story", createStoryMap(story))
                    eventMap.putMap("storyComponent", createStoryComponentMap(storyComponent))
                })
            }
        }

        storylyView.storylyProductListener = object : StorylyProductListener {
            override fun storylyUpdateCartEvent(
                storylyView: StorylyView,
                event: StorylyEvent,
                cart: STRCart?,
                change: STRCartItem?,
                onSuccess: ((STRCart?) -> Unit)?,
                onFail: ((STRCartEventResult) -> Unit)?
            ) {
                val failId = UUID.randomUUID().toString()
                val successId = UUID.randomUUID().toString()

                val eventParameters = Arguments.createMap()
                eventParameters.putMap("cart", cart?.let {
                    createSTRCartMap(it)
                } ?: Arguments.createMap())
                eventParameters.putString("failId", failId)
                eventParameters.putString("successId", successId)
                eventParameters.putMap("change", change?.let {
                    createSTRCartItemMap(it)
                } ?: Arguments.createMap())

                cartUpdateSuccessFailIdMap[failId] = successId
                cartUpdateSuccessFailIdMap[successId] = failId
                cartUpdateSuccessEvents[successId] = onSuccess
                cartUpdateFailEvents[failId] = onFail

                sendEvent(
                    STStorylyManager.EVENT_STORYLY_ON_CART_UPDATED,
                    eventParameters
                )
            }

            override fun storylyEvent(
                storylyView: StorylyView,
                event: StorylyEvent
            ) {
                sendEvent(
                    STStorylyManager.EVENT_STORYLY_PRODUCT_EVENT,
                    Arguments.createMap().also { eventMap ->
                        eventMap.putString("event", event.name)
                    }
                )
            }

            override fun storylyHydration(
                storylyView: StorylyView,
                productIds: List<String>
            ) {
                sendEvent(
                    STStorylyManager.EVENT_STORYLY_ON_HYDRATION,
                    Arguments.createMap().also { eventMap ->
                        eventMap.putArray(
                            "productIds",
                            Arguments.createArray().also { writableArray ->
                                productIds.forEach { writableArray.pushString(it) }
                            })
                    }
                )
            }
        }
    }

    internal var storyGroupViewFactory: STStoryGroupViewFactory? = null

    internal val activity: Context
        get() = ((context as? ReactContext)?.currentActivity ?: context)

    private val choreographerFrameCallback: Choreographer.FrameCallback by lazy {
        Choreographer.FrameCallback {
            if (isAttachedToWindow && storylyView?.isAttachedToWindow == true) {
                manuallyLayout()
                viewTreeObserver.dispatchOnGlobalLayout()
                Choreographer.getInstance().postFrameCallback(choreographerFrameCallback)
            }
        }
    }

    init {
        (context as? ReactContext)?.addLifecycleEventListener(object : LifecycleEventListener {
            override fun onHostResume() {
                val activity = (context as? ReactContext)?.currentActivity ?: return
                storylyView?.activity = WeakReference(activity)
            }

            override fun onHostPause() {}

            override fun onHostDestroy() {}
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(choreographerFrameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(choreographerFrameCallback)
    }

    internal fun onAttachCustomReactNativeView(child: View?, index: Int) {
        val storyGroupViewFactory = storyGroupViewFactory ?: return
        storyGroupViewFactory.attachCustomReactNativeView(child, index)
    }

    private fun manuallyLayout() {
        val storylyView = storylyView ?: return
        storylyView.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
        storylyView.layout(0, 0, storylyView.measuredWidth, storylyView.measuredHeight)

        val innerStorylyView = storylyView.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until innerStorylyView.childCount) {
            innerStorylyView.getChildAt(i).requestLayout()
        }
    }

    internal fun sendEvent(eventName: String, eventParameters: WritableMap?) {
        (context as? ReactContext)?.getJSModule(RCTEventEmitter::class.java)?.receiveEvent(id, eventName, eventParameters)
    }

    internal fun approveCart(successId: String, cart: STRCart? = null) {
        cartUpdateSuccessEvents[successId]?.invoke(cart)
        cartUpdateSuccessEvents.remove(successId)
        val failId = cartUpdateSuccessFailIdMap[successId]
        cartUpdateFailEvents.remove(failId)
        cartUpdateSuccessFailIdMap.remove(successId)
        cartUpdateSuccessFailIdMap.remove(failId)
    }

    internal fun rejectCart(failId: String, failMessage: String) {
        cartUpdateFailEvents[failId]?.invoke(STRCartEventResult(failMessage))
        cartUpdateFailEvents.remove(failId)
        val successId = cartUpdateSuccessFailIdMap[failId]
        cartUpdateSuccessEvents.remove(successId)
        cartUpdateSuccessFailIdMap.remove(successId)
        cartUpdateSuccessFailIdMap.remove(failId)
    }
}