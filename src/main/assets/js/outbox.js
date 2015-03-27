(function () {
   'use strict';
}());

angular.module('slicebox.outbox', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/outbox', {
    templateUrl: '/assets/partials/outbox.html',
    controller: 'OutboxCtrl'
  });
})

.controller('OutboxCtrl', function($scope, $http, $q, $interval, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteOutboxEntries
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outboxTable)) {
            $scope.callbacks.outboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };

    $scope.convertOutboxPageData = function(outboxEntries) {
        var outboxDataCollector = {};
        var outboxTransactionData;
        var imagesLeft;
        var pageData = [];

        var id = 1;
        angular.forEach(outboxEntries, function(outboxEntry) {
            outboxTransactionData = outboxDataCollector[outboxEntry.transactionId];
            if (angular.isUndefined(outboxTransactionData)) {
                outboxTransactionData =
                    {
                        id: id, 
                        remoteBoxName: outboxEntry.remoteBoxName,
                        totalImageCount: outboxEntry.totalImageCount,
                        failed: outboxEntry.failed,
                        outboxEntryIds: [],
                        imagesLeft: 0
                    };

                outboxDataCollector[outboxEntry.transactionId] = outboxTransactionData;
                id = id + 1;
            }

            outboxTransactionData.outboxEntryIds.push(outboxEntry.id);
            outboxTransactionData.imagesLeft = outboxTransactionData.imagesLeft + 1;
        });

        angular.forEach(outboxDataCollector, function(outboxTransactionData) {
            pageData.push(outboxTransactionData);
        });

        return pageData;
    };

    // Private functions
    function confirmDeleteOutboxEntries(outboxTransactionDataObjects) {
        var deleteConfirmationText = 'Permanently delete ' + outboxTransactionDataObjects.length + ' transactions?';

        return openConfirmationDeleteModal('Delete Transactions', deleteConfirmationText, function() {
            return deleteOutboxEntries(outboxTransactionDataObjects);
        });
    }

    function deleteOutboxEntries(outboxTransactionDataObjects) {
        var deletePromises = [];
        var deletePromise;
        var deleteAllPromies;

        angular.forEach(outboxTransactionDataObjects, function(outboxTransactionData) {
            for (var i = 0; i < outboxTransactionData.outboxEntryIds.length; i++) {
                deletePromise = $http.delete('/api/outbox/' + outboxTransactionData.outboxEntryIds[i]);
                deletePromises.push(deletePromise);
            }
        });

        deleteAllPromies = $q.all(deletePromises);

        deleteAllPromies.then(null, function(response) {
            $scope.appendErrorMessage(response.data);
        });

        return deleteAllPromies;
    }
});