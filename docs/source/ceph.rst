.. _Ceph:

Ceph
================================================================================

Ceph Overview
--------------------------------------------------------------------------------

Ceph is an open-source, massively scalable, software-defined storage system
which provides object, block and file system storage from a single clustered
platform. CephFs is the filesystem abstraction.

Ceph Storage Cluster
--------------------------------------------------------------------------------

Ceph storage cluster deployments begin with setting up each Ceph Node,
network and the Ceph Storage Cluster. A Ceph Storage Cluster requires at
least one Ceph Monitor and at least two Ceph OSD Daemons. Our Ceph storage
cluster includes five nodes - three nodes for Ceph monitors and two nodes for
Ceph OSDs. Additional, three Ceph-mgrs have been set up on each of the nodes
which is running a Ceph-mon daemon. By default, whichever Ceph-mgr instance
comes up first will be made active by the monitors, and the others will be
standbys. The following shows the information of our Ceph storage cluster.

.. figure::  images/ceph_storage_cluster_original_crop_bright.png
   :align:   center

Ceph Installation
--------------------------------------------------------------------------------

We use :ref:`Ansible` to install and configure Ceph through `the official
community Ceph repositories <http://download.ceph.com>`_.

Playbook
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Our configuration that deploys ``luminous`` version of Ceph with OSDs:

.. code-block:: yml

   ceph_origin = repository
   ceph_repository = community
   ceph_stable_release: luminous
   monitor_interface: eno49
   osd_scenario: non-collocated


.. note::

   For more information on our Ceph ansible playbooks please refer to 
   `<https://github.com/SDU-eScience/eScienceCloud/tree/master/playbooks/ceph>`_