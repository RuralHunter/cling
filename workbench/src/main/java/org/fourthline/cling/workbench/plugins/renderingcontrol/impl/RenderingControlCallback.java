/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.fourthline.cling.workbench.plugins.renderingcontrol.impl;

import org.fourthline.cling.controlpoint.SubscriptionCallback;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.GENASubscription;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.Channel;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable;
import org.fourthline.cling.workbench.Workbench;
import org.fourthline.cling.workbench.plugins.renderingcontrol.RenderingControlPoint;
import org.seamless.swing.logging.LogMessage;

import javax.swing.SwingUtilities;
import java.util.logging.Level;
import org.slf4j.*;

/**
 * @author Christian Bauer
 */
abstract public class RenderingControlCallback extends SubscriptionCallback {

    public RenderingControlCallback(Service service) {
        super(service);
    }

    @Override
    protected void failed(GENASubscription subscription,
                          UpnpResponse responseStatus,
                          Exception exception,
                          String defaultMsg) {
        RenderingControlPoint.LOGGER.error(defaultMsg);
    }

    public void established(GENASubscription subscription) {
        RenderingControlPoint.LOGGER.info(
            "Subscription with service established, listening for events."
        );
    }

    public void ended(GENASubscription subscription, final CancelReason reason, UpnpResponse responseStatus) {
        RenderingControlPoint.LOGGER.info("Subscription with service ended. " + (reason != null ? "Reason: " + reason : "")
        );
        onDisconnect(reason);
    }

    public void eventReceived(GENASubscription subscription) {
        RenderingControlPoint.LOGGER.debug(
            "Event received, sequence number: " + subscription.getCurrentSequence()
        );

        final LastChange lastChange;
        try {
            lastChange = new LastChange(
                    new RenderingControlLastChangeParser(),
                    subscription.getCurrentValues().get("LastChange").toString()
            );
        } catch (Exception ex) {
            RenderingControlPoint.LOGGER.warn(
                "Error parsing LastChange event content: " + ex
            );
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                for (UnsignedIntegerFourBytes instanceId : lastChange.getInstanceIDs()) {

                    RenderingControlPoint.LOGGER.debug(
                        "Processing LastChange event values for instance: " + instanceId
                    );
                    RenderingControlVariable.Volume volume = lastChange.getEventedValue(
                            instanceId,
                            RenderingControlVariable.Volume.class
                    );
                    if (volume != null && volume.getValue().getChannel().equals(Channel.Master)) {
                        RenderingControlPoint.LOGGER.debug(
                            "Received new volume value for 'Master' channel: " + volume.getValue()
                        );
                        onMasterVolumeChanged(
                                new Long(instanceId.getValue()).intValue(),
                                volume.getValue().getVolume()
                        );
                    }
                }
            }
        });
    }

    public void eventsMissed(GENASubscription subscription, int numberOfMissedEvents) {
        RenderingControlPoint.LOGGER.warn(
            "Events missed (" + numberOfMissedEvents + "), consider restarting this control point!"
        );
    }

    abstract protected void onDisconnect(CancelReason reason);

    abstract protected void onMasterVolumeChanged(int instanceId, int newVolume);
}
